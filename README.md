# JSON Logger - Mule 4

Drop-in replacement for the default Mule Logger that writes structured JSON log entries, so log aggregators such as Splunk, ELK, Datadog and CloudWatch can parse them.

## Why?

- Logs are only as useful as the logging practices behind them, and developers rarely agree on what to log
- Basics like tying every log entry of a transaction together with a correlation id are often applied inconsistently, which makes troubleshooting much harder
- Log aggregation platforms work best with key/value or JSON data. Only then can you build useful dashboards, alerts and reports

JSON Logger enforces one consistent, schema-driven log structure for every Mule application that uses it.

## How?

JSON Logger is a Java SDK based Mule 4 connector. The output structure and the connector configuration are generated from two JSON schemas:

> `json-logger/src/main/resources/schema/loggerConfig.json` (global config)
> `json-logger/src/main/resources/schema/loggerProcessor.json` (logger operation)

Edit these schemas (fields, defaults, DataWeave expressions, ...) and rebuild to tailor the connector to your organisation's standards. You don't need to write any Java.

## Compatibility

| JSON Logger | Mule runtime | Java |
|---|---|---|
| 3.x | 4.9.0+ (tested on 4.13.0) | 17, 21 |
| 2.1.x | 4.3.0 - 4.8.x | 8, 11 |

## Features (3.x)

- Single-line JSON with correlation id, trace point, priority, elapsed time, location, timestamp, application and environment info
- OpenTelemetry trace correlation (`traceId`, `spanId`, `traceFlags`) and logging context (MDC) enrichment
- Custom top-level fields from DataWeave (`customFields`), plus automatic `error` details when used inside an error handler
- Data protection: JSON key / JSON path masking (`PRESERVE_FORMAT`, `FULL` or `HASH`) and regex redaction of any string value (`EMAIL`, `CREDIT_CARD`, `SSN`, `JWT`, `BEARER_TOKEN`, `IPV4` or a custom regex)
- `maxContentLength` truncation to protect logs from very large payloads
- Logger scope that measures the elapsed time of any group of components
- Asynchronous, batched forwarding to external destinations (Anypoint MQ, JMS, AMQP)
- A logger never fails the flow it is logging: any internal error is reported to the connector's own log and the flow continues

---

## Getting started

### 1. Build and publish the connector

Prerequisites: **JDK 17** (make sure `JAVA_HOME` points to it) and **Maven 3.9+** with access to the MuleSoft repositories (see [`template-files/settings.xml`](template-files/settings.xml)).

**Option A - publish to your Anypoint Exchange** (recommended for teams):

```bash
./deploy-to-exchange.sh <ANYPOINT_ORG_ID>
```

The script replaces `ORG_ID_TOKEN` in `json-logger/pom.xml` with your organisation id and runs `mvn clean deploy`. The `Exchange2` server credentials must be set in your Maven `settings.xml`.

- Exchange doesn't accept the same version twice. Bump `<version>` in `json-logger/pom.xml` before you redeploy, or delete the asset within 7 days.
- For the **EU control plane**, change the `<distributionManagement>` URL (and the `Exchange2` repository) in `json-logger/pom.xml` to `https://maven.eu1.anypoint.mulesoft.com/api/v1/organizations/${project.groupId}/maven`.

**Option B - install to your local Maven repository** (local development / try-out):

```bash
mvn -f json-logger/pom.xml clean install
```

This installs the connector under groupId `ORG_ID_TOKEN`, version `3.0.0`.

### 2. Add the dependency to your Mule application

```xml
<dependency>
    <groupId>YOUR_ORG_ID</groupId>   <!-- ORG_ID_TOKEN if installed locally with option B -->
    <artifactId>json-logger</artifactId>
    <version>3.0.0</version>
    <classifier>mule-plugin</classifier>
</dependency>
```

### 3. Add the properties file

Copy [`template-files/json-logger.properties`](template-files/json-logger.properties) to `src/main/resources` and enable resource filtering in your app's `pom.xml`. Maven then injects the application name and version:

```xml
<build>
    <resources>
        <resource>
            <directory>src/main/resources</directory>
            <filtering>true</filtering>
            <includes><include>json-logger.properties</include></includes>
        </resource>
        <resource>
            <directory>src/main/resources</directory>
            <filtering>false</filtering>
            <excludes><exclude>json-logger.properties</exclude></excludes>
        </resource>
    </resources>
</build>
```

```xml
<configuration-properties file="json-logger.properties"/>
```

### 4. Configure and log

```xml
<json-logger:config name="JSON_Logger_Config" environment="${mule.env}"
                    contentFieldsDataMasking="password,$..card.number" maskingStrategy="HASH"
                    redactionPatterns="EMAIL,JWT" maxContentLength="10000" logMdcFields="true"/>

<flow name="orders">
    <http:listener config-ref="HTTP_Listener_config" path="/orders"/>

    <json-logger:logger config-ref="JSON_Logger_Config" message="Order received" tracePoint="START">
        <json-logger:custom-fields>#[output application/json --- {orderId: vars.orderId}]</json-logger:custom-fields>
    </json-logger:logger>

    <!-- Logs BEFORE / AFTER entries with the time spent inside the scope (or EXCEPTION_SCOPE on failure) -->
    <json-logger:logger-scope configurationRef="JSON_Logger_Config" scopeTracePoint="OUTBOUND_REQUEST_SCOPE"
                              message="Calling Salesforce">
        <http:request config-ref="Salesforce_Config" method="POST" path="/orders"/>
    </json-logger:logger-scope>

    <json-logger:logger config-ref="JSON_Logger_Config" message="Order processed" tracePoint="END"/>

    <error-handler>
        <on-error-propagate>
            <!-- error details are added automatically -->
            <json-logger:logger config-ref="JSON_Logger_Config" message="Order failed" tracePoint="EXCEPTION" priority="ERROR"/>
        </on-error-propagate>
    </error-handler>
</flow>
```

Example output (formatted here for readability, written as a single line by default):

```json
{
  "correlationId": "4f1c2a10-...",
  "message": "Order received",
  "tracePoint": "START",
  "priority": "INFO",
  "category": "org.mule.extension.jsonlogger.JsonLogger",
  "elapsed": 0,
  "locationInfo": { "rootContainer": "orders", "component": "json-logger:logger", "fileName": "orders.xml", "lineInFile": "21" },
  "timestamp": "2026-11-23T19:51:32.949Z",
  "content": { "payload": { "orderId": "A-42", "password": "sha256:9f86d081884c7d65" } },
  "orderId": "A-42",
  "applicationName": "orders-api",
  "applicationVersion": "1.0.0",
  "environment": "dev",
  "threadName": "[MuleRuntime].uber.03: [orders-api].orders.CPU_LITE @6a1c2b3d",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7"
}
```

A runnable app that exercises every feature lives in [examples/json-logger-demo-app](examples/json-logger-demo-app).

---

## Reference

### Global configuration (`json-logger:config`)

| Attribute | Default | Description |
|---|---|---|
| `applicationName` / `applicationVersion` | `${json.logger.application.name}` / `${json.logger.application.version}` | Injected from your `pom.xml` via `json-logger.properties` |
| `environment` | (required) | E.g. `${mule.env}` |
| `prettyPrint` | `false` | Multi-line output. Keep single-line for log aggregators |
| `logLocationInfo` | `true` | Adds `locationInfo` (flow, component, file, line) |
| `parseContentFieldsInJsonOutput` | `true` | JSON content is embedded as structured JSON instead of an escaped string |
| `disabledFields` | | Comma separated output fields to drop, e.g. `content,threadName,locationInfo`. Disabled content fields are never evaluated |
| `contentFieldsDataMasking` | | Comma separated JSON keys (case-insensitive) or JSON paths (`$..card.number`) to mask inside JSON content fields |
| `maskingStrategy` | `PRESERVE_FORMAT` | `PRESERVE_FORMAT` (`Xxxxx**`), `FULL` (`****`) or `HASH` (`sha256:<16 hex>`, so masked values can still be correlated) |
| `redactionPatterns` | | Comma separated built-ins (`EMAIL`, `CREDIT_CARD`, `SSN`, `JWT`, `BEARER_TOKEN`, `IPV4`) or regexes. Applied to every string value except standard fields such as `correlationId`. An invalid regex fails app startup |
| `maxContentLength` | `0` (no limit) | Max characters per content field, longer values end with `...[TRUNCATED]`. `customFields` are never truncated |
| `logTraceContext` | `true` | Adds `traceId`, `spanId`, `traceFlags` when an OpenTelemetry span is active |
| `logMdcFields` | `false` | Adds all MDC entries (e.g. Tracing module *Set logging variable*) under `context` |
| `logHostInfo` | `false` | Adds `hostName` and `workerId` |
| External destination (Destinations tab) | | Anypoint MQ, JMS or AMQP. See [External destinations](#external-destinations) |

### Logger operation (`json-logger:logger`)

| Attribute | Default | Description |
|---|---|---|
| `message` | | Free-text message |
| `tracePoint` | `START` | `START`, `BEFORE_TRANSFORM`, `AFTER_TRANSFORM`, `BEFORE_REQUEST`, `AFTER_REQUEST`, `FLOW`, `END`, `EXCEPTION` |
| `priority` | `INFO` | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`. If the level isn't enabled for the category, the entry is skipped and no expressions are evaluated |
| `category` | `org.mule.extension.jsonlogger.JsonLogger` | Log4j category, e.g. to route audit logs to a different appender |
| `correlationId` | `#[correlationId]` | |
| `content` | `#[{payload: ...}]` | Any DataWeave expression. JSON (incl. `+json` media types) is parsed, masked and truncated |
| `customFields` | | DataWeave object merged as **top-level** fields. Cannot override standard fields |
| `error` | Mule `error` details | Populated automatically inside error handlers (`errorType`, `description`, `detailedDescription`, `failingComponent`) |

### Logger scope (`json-logger:logger-scope`)

| Attribute | Default | Description |
|---|---|---|
| `configurationRef` | (required) | Name of the global JSON Logger config. If it doesn't exist, the scope still runs without logging |
| `scopeTracePoint` | `OUTBOUND_REQUEST_SCOPE` | `OUTBOUND_REQUEST_SCOPE`, `DATA_TRANSFORM_SCOPE`, `FLOW_LOGIC_SCOPE`. Entries are logged as `<tracePoint>_BEFORE` / `_AFTER`, or `EXCEPTION_SCOPE` with an `error` object on failure |
| `priority`, `category`, `message`, `correlationId` | | Same as the logger operation |

The `_AFTER` entry includes `scopeElapsed`, the time spent inside the scope in milliseconds.

### System properties

| Property | Default | Description |
|---|---|---|
| `json.logger.timezone` | `UTC` | Any [tz database](https://en.wikipedia.org/wiki/List_of_tz_database_time_zones) zone. Invalid values fall back to UTC |
| `json.logger.dateformat` | ISO-8601 with millis (`yyyy-MM-dd'T'HH:mm:ss.SSSXXX`) | Any `java.time` pattern. Invalid patterns fall back to ISO-8601 |
| `json.logger.destinations.buffersize` | `1024` | Ring buffer size for external destinations (power of 2) |
| `json.logger.destinations.waittimeout` | `100` | Milliseconds to wait for buffer space before an event may be dropped |

### External destinations

Log entries can also be forwarded asynchronously to a message broker. Entries are buffered in an LMAX Disruptor ring buffer and sent as a JSON array once `maxBatchSize` (default `25`) is reached, or when the buffer drains.

| Destination | Requirements |
|---|---|
| **Anypoint MQ** | Region URL, client app id and secret. Uses its own HTTP client, no connector needed |
| **JMS** | A JMS connector config in the app (`jmsConfigurationRef`) and a target queue |
| **AMQP** | An AMQP connector config in the app (`amqpConfigurationRef`) and a target exchange |

Use `logCategories` to forward only specific categories (empty = all). Send failures are logged but never affect the flow.

### OpenTelemetry

`traceId` and `spanId` come from the Mule 4.9+ tracer. On a standalone runtime, enable OTel export (e.g. `-Dmule.openTelemetry.tracer.exporter.enabled=true` plus your collector endpoint). Incoming W3C `traceparent` headers are honoured. Without an active span the fields are left out.

---

## Development

```bash
cd json-logger
mvn clean verify        # builds the connector, runs unit tests and validates the extension model
```

The build generates the config and operation POJOs from the JSON schemas into `target/generated-sources` (jsonschema2pojo). Run `mvn generate-sources` after editing a schema so your IDE picks up the changes.

### Tests

The unit tests (JUnit 4 + Mockito) run as part of `mvn verify` and don't need a Mule runtime:

| Test | Covers |
|---|---|
| `JsonloggerOperationsTest` | End-to-end output of the `logger` and `logger-scope` operations, asserted on the captured JSON lines: field order, priorities, content parsing, masking, redaction, truncation, custom fields, disabled fields, MDC/trace context, elapsed timers, scope success/error and external destination publishing |
| `LogEventHandlerTest`, `LogEventSingletonTest` | Batching and flushing of external destination events, including the real Disruptor pipeline |
| `DestinationsTest` | JMS / AMQP / Anypoint MQ message construction and failure isolation |
| `JsonMaskerTest`, `PatternRedactorTest`, `ContextEnricherTest`, `TimestampFormatterTest`, `TruncateTest` | Individual helpers |

Line coverage is about 80%. The code that's not covered is mostly the Anypoint MQ HTTP client adapter (`AsyncMuleCourierRestClient`), which needs a live broker. Check it with the demo app.

To check that the connector works in a real runtime, install it locally (`mvn install`) and deploy [examples/json-logger-demo-app](examples/json-logger-demo-app) to a Mule 4.9+ runtime on Java 17.

## Release notes

See [json-logger/README.md](json-logger/README.md).

Background blog posts (written for 1.x/2.x, but the concepts still apply):

- [Part 1: JSON logging in Mule 4 - getting the most out of your logs](https://blogs.mulesoft.com/dev/anypoint-platform-dev/json-logging-in-mule-4-getting-the-most-out-of-your-logs/)
- [Part 2: JSON logging in Mule 4](https://blogs.mulesoft.com/dev/api-dev/json-logging-in-mule-4/)

## Author

* **Andres Ramirez** [Slack: @andres.ramirez / Email: andres.ramirez@mulesoft.com]

## Support disclaimer

This is a side project provided under the MIT open source license. MuleSoft does not officially support it, as it is considered a custom connector.
