# JSON Logger demo app

Small Mule 4.9+ (Java 17) app that exercises every JSON Logger 3.x feature: content parsing, masking strategies,
regex redaction, custom fields, MDC / logging variables, automatic error details, logger scope, truncation, pretty print,
custom categories and OpenTelemetry trace correlation.

```bash
# 1. install the connector locally
mvn -f ../../json-logger/pom.xml clean install
# 2. build the app (groupId ORG_ID_TOKEN matches the un-tokenised connector pom)
mvn clean package
# 3. copy target/*-mule-application.jar to <MULE_HOME>/apps and start the runtime under Java 17
```

| Endpoint | What it shows |
|---|---|
| `GET :8099/basic?orderId=A-42` | custom fields, MDC context, JSON-key + JSON-path masking, EMAIL redaction |
| `GET :8099/scope` | logger scope BEFORE/AFTER entries with `message` |
| `GET :8099/fail` | scope `EXCEPTION_SCOPE` entry + automatic `error` object in an error handler |
| `GET :8099/pretty` | pretty print, custom category, `HASH` masking, `maxContentLength` truncation, disabled fields |

JSON entries are written to `<MULE_HOME>/logs/json-only.log`. To get `traceId`/`spanId`, start the runtime with
`-Dmule.openTelemetry.tracer.exporter.enabled=true` (and an exporter endpoint) and optionally send a W3C `traceparent` header.
