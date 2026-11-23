# Json-logger Extension

## 3.0.0 version - Release notes

**Breaking / platform changes**
* Minimum supported Mule runtime is now **4.9.0**. Built and tested on **Java 17** (Java 21 declared as supported). Verified on Mule **4.13.0** standalone runtime
* Rebased on `mule-modules-parent` 1.9.x (Mule SDK 1.9, `mule-sdk-api`), Java release 17
* `prettyPrint` now **defaults to `false`** (single-line JSON). Multi-line entries are split into separate events by most log aggregators (Splunk, ELK, Datadog, CloudWatch). Set `prettyPrint="true"` to restore the previous behaviour
* Default timestamp format now always includes milliseconds and a zone offset (ISO-8601, e.g. `2026-01-01T10:15:30.123Z`). `json.logger.dateformat` / `json.logger.timezone` still apply
* Removed Joda-Time (replaced by thread-safe `java.time`), commons-lang 2 (replaced by commons-lang3), unused `org.json` and `snakeyaml` dependencies. Upgraded Jackson, json-path, gson, commons-beanutils, disruptor

**New features**
* **OpenTelemetry trace correlation** (`logTraceContext`, default `true`): `traceId`, `spanId` and `traceFlags` are added whenever the runtime has an active OpenTelemetry span, so log entries can be joined with traces in any OTel backend (incoming W3C `traceparent` headers are honoured). On a standalone runtime this requires OTel tracing export to be enabled (e.g. `-Dmule.openTelemetry.tracer.exporter.enabled=true` plus your collector endpoint); without an active span the fields are simply omitted
* **Logging context / MDC** (`logMdcFields`, default `false`): all MDC entries (e.g. Tracing module `Set logging variable`) are added under `context`
* **Custom fields** (`customFields`): add any structured key/value pairs (DataWeave object) as top-level fields, e.g. `#[{orderId: vars.orderId}]`. Standard fields can never be overridden
* **Automatic error details** (`error`): when the logger runs inside an error handler, `errorType`, `description`, `detailedDescription` and `failingComponent` are logged automatically
* **Regex based PII / secret redaction** (`redactionPatterns`): applies to every string value of the log entry (message, content, custom fields...). Built-ins: `EMAIL`, `CREDIT_CARD`, `SSN`, `JWT`, `BEARER_TOKEN`, `IPV4`, or any custom regular expression
* **Masking strategies** (`maskingStrategy`): `PRESERVE_FORMAT` (previous behaviour), `FULL` (`****`) or `HASH` (SHA-256 fingerprint so masked values can still be correlated)
* **Content truncation** (`maxContentLength`): protects logs from huge payloads, content fields longer than the limit are truncated with a `...[TRUNCATED]` marker
* **Host info** (`logHostInfo`, default `false`): `hostName` and `workerId`
* Logger scope: new optional `message` parameter, `category` is now part of the output and scope errors include an `error` object
* `disabledFields` can now disable any output field (e.g. `threadName`, `locationInfo`, `elapsed`), not only operation parameters
* JSON content detection now supports `+json` media types (e.g. `application/vnd.api+json`)

**Security**
* Upgraded libraries bundled in the plugin to patched versions: Jackson 2.18.12 (fixes critical deserialization CVEs), commons-lang3 3.18.0, json-path 2.10.0, json-smart 2.6.1. Guava (pulled in by the Anypoint MQ client) is pinned to 33.7.2-jre
* These are declared as direct dependencies so consuming applications also resolve the patched versions. A Snyk scan of the demo app reports 0 known vulnerabilities

**Bug fixes**
* Fixed race condition: the logger category was stored in a shared field of the (singleton) operations instance, so concurrent flows using different categories could log to the wrong category
* Fixed thread-safety of the external destinations ring buffer (`ProducerType.MULTI`) and configs registry
* Masking configuration, disabled fields and redaction patterns are now parsed once at startup rather than on every log call
* A failure while building a log entry can no longer fail the flow being logged
* Logger scope no longer fails with a `NullPointerException` when `configurationRef` points to a non-existing config (it logs an error and runs the scope)
* Data masking now trims whitespace in the configured key list (`password, ssn`) and also masks boolean values

## 2.1.0 version - Release notes

* Minimum supported mule runtime 4.3
* Upgraded dependencies to fix known vulnerabilities

## 2.0.1 version - Release notes

Bug fixes:
* Added support for large payloads

## 2.0.0 version - Release notes

New features:
* External Destinations
* Data masking

Improvements:
* Field ordering

More details in the coming blog post (stay tuned!)

## 1.1.0 version - Release notes

New features:
* Scoped loggers to capture "scope bound elapsed time". Great for performance tracking of specific components (e.g. outbound calls)
* Added "Parse content fields in json output" flag so that content fields can become part of final JSON output rather than a "stringified version" of the content

Improvements:
* Removed Guava and caching in general with a more efficient handling of timers (for elapsed time)
* Optimized generation of JSON output
* Code optimizations
* Minimized dependency footprint (down from ~23MB to ~13MB)
* Optimized parsing of TypedValue content fields

