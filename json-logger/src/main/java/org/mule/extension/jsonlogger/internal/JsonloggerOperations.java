package org.mule.extension.jsonlogger.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.beanutils.PropertyUtils;
import org.mule.extension.jsonlogger.api.pojos.JsonOutput;
import org.mule.extension.jsonlogger.api.pojos.LoggerProcessor;
import org.mule.extension.jsonlogger.api.pojos.Priority;
import org.mule.extension.jsonlogger.api.pojos.ScopeTracePoint;
import org.mule.extension.jsonlogger.internal.datamask.JsonMasker;
import org.mule.extension.jsonlogger.internal.datamask.PatternRedactor;
import org.mule.extension.jsonlogger.internal.singleton.ConfigsSingleton;
import org.mule.extension.jsonlogger.internal.singleton.LogEventSingleton;
import org.mule.extension.jsonlogger.internal.singleton.ObjectMapperSingleton;
import org.mule.extension.jsonlogger.internal.util.ContextEnricher;
import org.mule.extension.jsonlogger.internal.util.TimestampFormatter;
import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.api.meta.model.operation.ExecutionType;
import org.mule.runtime.api.metadata.MediaType;
import org.mule.runtime.api.metadata.TypedValue;
import org.mule.runtime.api.transformation.TransformationService;
import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.execution.Execution;
import org.mule.runtime.extension.api.annotation.param.Config;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.ParameterGroup;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Example;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.runtime.extension.api.runtime.operation.FlowListener;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.runtime.extension.api.runtime.parameter.CorrelationInfo;
import org.mule.runtime.extension.api.runtime.parameter.ParameterResolver;
import org.mule.runtime.extension.api.runtime.process.CompletionCallback;
import org.mule.runtime.extension.api.runtime.route.Chain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.InputStream;
import java.util.*;

import static org.mule.runtime.api.meta.ExpressionSupport.NOT_SUPPORTED;
import static org.mule.runtime.api.metadata.DataType.TEXT_STRING;

/**
 * This class is a container for operations, every public method in this class will be taken as an extension operation.
 * <p>
 * NOTE: instances of this class are shared across concurrent executions, so it must remain stateless.
 */
public class JsonloggerOperations {

    public static final String DEFAULT_CATEGORY = "org.mule.extension.jsonlogger.JsonLogger";
    static final String TRUNCATED_SUFFIX = "...[TRUNCATED]";

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonloggerOperations.class);

    // Fields that are produced by the logger itself and should never be redacted or overridden by custom fields
    private static final Set<String> RESERVED_FIELDS = new HashSet<>(Arrays.asList(
            "correlationId", "tracePoint", "priority", "category", "elapsed", "scopeElapsed", "locationInfo",
            "timestamp", "applicationName", "applicationVersion", "environment", "threadName",
            "traceId", "spanId", "traceFlags", "hostName", "workerId"));

    // Fields that contain values that are never sensitive (skip regex redaction for performance & accuracy)
    private static final Set<String> NON_REDACTABLE_FIELDS = new HashSet<>(Arrays.asList(
            "correlationId", "tracePoint", "priority", "category", "elapsed", "scopeElapsed", "locationInfo",
            "timestamp", "applicationName", "applicationVersion", "environment", "threadName",
            "traceId", "spanId", "traceFlags"));

    // Content fields handled as TypedValues. "customFields" is merged into the top level instead of being nested
    private static final String CUSTOM_FIELDS = "customFields";

    // Void Result for NIO
    private static final Result<Void, Void> VOID_RESULT = Result.<Void, Void>builder().build();

    // JSON Object Mapper
    @Inject
    ObjectMapperSingleton om;

    // Log Event for External Destination
    @Inject
    LogEventSingleton logEvent;

    // Global definition of logger configs so that it's available for scope processor (SDK scope doesn't support passing configurations)
    @Inject
    ConfigsSingleton configs;

    // Transformation Service
    @Inject
    private TransformationService transformationService;

    /**
     * Log a new entry
     */
    @Execution(ExecutionType.BLOCKING)
    public void logger(@ParameterGroup(name = "Logger") @Expression(value = NOT_SUPPORTED) LoggerProcessor loggerProcessor,
                       CorrelationInfo correlationInfo,
                       ComponentLocation location,
                       @Config JsonloggerConfiguration config,
                       FlowListener flowListener,
                       CompletionCallback<Void, Void> callback) {

        long loggerTimestamp = System.currentTimeMillis();
        Logger jsonLogger = getLogger(loggerProcessor.getCategory());
        String priority = loggerProcessor.getPriority().toString();

        long elapsed = computeElapsed(config, correlationInfo, flowListener, loggerTimestamp);

        // Avoid Logger logic execution based on log priority
        if (!isLogEnabled(jsonLogger, priority)) {
            LOGGER.debug("Avoiding logger operation logic execution due to log priority not being enabled");
            callback.success(VOID_RESULT);
            return;
        }

        try {
            JsonOutput jsonOutput = config.getJsonOutput();
            List<String> disabledFields = config.getDisabledFieldsList();
            ObjectMapper mapper = om.getObjectMapper();

            // Logic to disable fields and/or parse TypedValues as String / JSON for log printing
            Map<String, JsonNode> contentFields = new LinkedHashMap<>();
            JsonNode customFields = null;
            try {
                for (Map.Entry<String, Object> entry : PropertyUtils.describe(loggerProcessor).entrySet()) {
                    String k = entry.getKey();
                    Object v = entry.getValue();
                    if (disabledFields.contains(k)) {
                        setPropertyQuietly(loggerProcessor, k, null);
                        continue;
                    }
                    if (v == null) {
                        continue;
                    }
                    try {
                        if (v instanceof ParameterResolver) {
                            v = ((ParameterResolver<?>) v).resolve();
                        }
                        if (v instanceof TypedValue) {
                            // Remove unparsed field
                            setPropertyQuietly(loggerProcessor, k, null);
                            JsonNode parsed = parseTypedValue(k, (TypedValue<?>) v, config);
                            if (parsed == null || parsed.isNull() || parsed.isMissingNode()) {
                                continue;
                            }
                            if (CUSTOM_FIELDS.equals(k)) {
                                customFields = parsed;
                            } else {
                                contentFields.put(k, parsed);
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.error("Failed parsing field: " + k, e);
                        setPropertyQuietly(loggerProcessor, k, null);
                        contentFields.put(k, mapper.getNodeFactory().textNode("Error parsing expression. See logs for details."));
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Unknown error while processing the logger object", e);
            }

            // Aggregate Logger data into mergedLogger
            ObjectNode mergedLogger = mapper.createObjectNode();
            mergedLogger.setAll((ObjectNode) mapper.valueToTree(loggerProcessor));
            mergedLogger.remove(CUSTOM_FIELDS);

            /*
             * Custom field ordering for Logger Operation
             * ==========================================
             * This will take place after LoggerProcessor ordering which is defined by the field sequence in loggerProcessor.json
             */
            // 1. Elapsed Time
            mergedLogger.put("elapsed", elapsed);
            // 2. Location Info: Logger location within Mule application
            if (jsonOutput.isLogLocationInfo()) {
                mergedLogger.set("locationInfo", locationInfoToNode(location));
            }
            // 3. Timestamp: Add formatted timestamp entry to the logger
            mergedLogger.put("timestamp", TimestampFormatter.format(loggerTimestamp));
            // 4. Content fields (String or JSON)
            contentFields.forEach(mergedLogger::set);
            // 5. Custom (structured) fields. They never override standard fields
            mergeCustomFields(mergedLogger, customFields);
            // 6. Global info from config
            mergedLogger.setAll((ObjectNode) mapper.valueToTree(config.getGlobalSettings()));
            // 7. Runtime context
            addRuntimeContext(mergedLogger, jsonOutput);
            /* End field ordering */

            // Allow disabling any output field (not only the ones defined in loggerProcessor.json)
            disabledFields.forEach(mergedLogger::remove);

            // Regex based redaction (PII / secrets) across all string values
            PatternRedactor redactor = config.getRedactor();
            if (redactor != null) {
                redactor.redactInPlace(mergedLogger, NON_REDACTABLE_FIELDS);
            }

            /* Print Logger */
            String finalLog = printObjectToLog(jsonLogger, mergedLogger, priority, jsonOutput.isPrettyPrint());

            /* Forward Log to External Destination */
            if (config.getExternalDestination() != null && isSupportedCategory(config, jsonLogger.getName())) {
                LOGGER.debug("{} is a supported category for external destination", jsonLogger.getName());
                logEvent.publishToExternalDestination(correlationInfo.getEventId(), finalLog, config.getConfigName());
            }
        } catch (Exception e) {
            // A logger should never break the flow it is logging
            LOGGER.error("Unexpected error while generating JSON log entry", e);
        }
        callback.success(VOID_RESULT);
    }

    /**
     * Log scope
     */
    @Execution(ExecutionType.BLOCKING)
    @org.mule.runtime.extension.api.annotation.param.MediaType(value = "*/*", strict = false)
    public void loggerScope(@DisplayName("Module configuration") @Example("JSON_Logger_Config") @Summary("Indicate which Global config should be associated with this Scope.") String configurationRef,
                            @Optional(defaultValue = "INFO") Priority priority,
                            @Optional(defaultValue = "OUTBOUND_REQUEST_SCOPE") ScopeTracePoint scopeTracePoint,
                            @Optional @Summary("If not set, by default will log to the org.mule.extension.jsonlogger.JsonLogger category") String category,
                            @Optional @Summary("Optional message added to the BEFORE/AFTER scope log entries (e.g. 'Calling Salesforce')") String message,
                            @Optional(defaultValue = "#[correlationId]") @Placement(tab = "Advanced") String correlationId,
                            ComponentLocation location,
                            CorrelationInfo correlationInfo,
                            FlowListener flowListener,
                            Chain operations,
                            CompletionCallback<Object, Object> callback) {

        /*
         * BEFORE scope logger
         * ===================
         */
        long loggerTimestamp = System.currentTimeMillis();
        Logger jsonLogger = getLogger(category);

        JsonloggerConfiguration config = configs.getConfig(configurationRef);
        if (config == null) {
            LOGGER.error("JSON Logger configuration '{}' referenced by logger scope at {} was not found. Executing scope without logging.",
                    configurationRef, location.getLocation());
            operations.process(callback::success, (error, previous) -> callback.error(error));
            return;
        }

        long elapsed = computeElapsed(config, correlationInfo, flowListener, loggerTimestamp);

        // Avoid Logger Scope logic execution based on log priority
        if (!isLogEnabled(jsonLogger, priority.toString())) {
            LOGGER.debug("Avoiding logger scope logic execution due to log priority not being enabled");
            operations.process(callback::success, (error, previous) -> callback.error(error));
            return;
        }

        JsonOutput jsonOutput = config.getJsonOutput();
        ObjectNode loggerProcessor = om.getObjectMapper().createObjectNode();

        /*
         * Custom field ordering for Logger Scope
         * ===============================
         */
        loggerProcessor.put("correlationId", correlationId);
        if (message != null) {
            loggerProcessor.put("message", message);
        }
        loggerProcessor.put("tracePoint", scopeTracePoint.toString() + "_BEFORE");
        loggerProcessor.put("priority", priority.toString());
        loggerProcessor.put("category", jsonLogger.getName());
        loggerProcessor.put("elapsed", elapsed);
        loggerProcessor.put("scopeElapsed", 0);
        if (jsonOutput.isLogLocationInfo()) {
            loggerProcessor.set("locationInfo", locationInfoToNode(location));
        }
        loggerProcessor.put("timestamp", TimestampFormatter.format(loggerTimestamp));
        loggerProcessor.setAll((ObjectNode) om.getObjectMapper().valueToTree(config.getGlobalSettings()));
        addRuntimeContext(loggerProcessor, jsonOutput);
        config.getDisabledFieldsList().forEach(loggerProcessor::remove);

        String finalLogBefore = printObjectToLog(jsonLogger, loggerProcessor, priority.toString(), jsonOutput.isPrettyPrint());

        operations.process(
                result -> {
                    /*
                     * AFTER scope logger
                     * ===================
                     */
                    try {
                        long endScopeTimestamp = System.currentTimeMillis();
                        loggerProcessor.put("tracePoint", scopeTracePoint.toString() + "_AFTER");
                        loggerProcessor.put("elapsed", elapsed + (endScopeTimestamp - loggerTimestamp));
                        loggerProcessor.put("scopeElapsed", endScopeTimestamp - loggerTimestamp);
                        loggerProcessor.put("timestamp", TimestampFormatter.format(endScopeTimestamp));
                        loggerProcessor.put("threadName", Thread.currentThread().getName());
                        config.getDisabledFieldsList().forEach(loggerProcessor::remove);

                        String finalLogAfter = printObjectToLog(jsonLogger, loggerProcessor, priority.toString(), jsonOutput.isPrettyPrint());
                        publishScopeLogEvents(config, jsonLogger, correlationId, finalLogBefore, finalLogAfter);
                    } catch (Exception e) {
                        LOGGER.error("Unexpected error while generating JSON scope log entry", e);
                    }
                    callback.success(result);
                },
                (error, previous) -> {
                    /* ERROR scope logger */
                    try {
                        long errorScopeTimestamp = System.currentTimeMillis();
                        loggerProcessor.put("message", "Error found: " + error.getMessage());
                        loggerProcessor.put("tracePoint", "EXCEPTION_SCOPE");
                        loggerProcessor.put("priority", "ERROR");
                        loggerProcessor.put("elapsed", elapsed + (errorScopeTimestamp - loggerTimestamp));
                        loggerProcessor.put("scopeElapsed", errorScopeTimestamp - loggerTimestamp);
                        loggerProcessor.put("timestamp", TimestampFormatter.format(errorScopeTimestamp));
                        loggerProcessor.put("threadName", Thread.currentThread().getName());
                        ObjectNode errorNode = loggerProcessor.putObject("error");
                        errorNode.put("type", error.getClass().getName());
                        if (error.getMessage() != null) {
                            errorNode.put("description", error.getMessage());
                        }
                        config.getDisabledFieldsList().forEach(loggerProcessor::remove);

                        PatternRedactor redactor = config.getRedactor();
                        if (redactor != null) {
                            redactor.redactInPlace(loggerProcessor, NON_REDACTABLE_FIELDS);
                        }

                        String finalLogError = printObjectToLog(jsonLogger, loggerProcessor, "ERROR", jsonOutput.isPrettyPrint());
                        publishScopeLogEvents(config, jsonLogger, correlationId, finalLogBefore, finalLogError);
                    } catch (Exception e) {
                        LOGGER.error("Unexpected error while generating JSON scope error log entry", e);
                    }
                    callback.error(error);
                });
    }

    /* ======================================================================== */
    /* Helpers                                                                  */
    /* ======================================================================== */

    /**
     * Parse a content (TypedValue) field into a JsonNode, applying masking and truncation.
     */
    private JsonNode parseTypedValue(String field, TypedValue<?> typedVal, JsonloggerConfiguration config) throws Exception {
        Object value = typedVal.getValue();
        if (value == null) {
            return null;
        }
        ObjectMapper mapper = om.getObjectMapper();
        JsonOutput jsonOutput = config.getJsonOutput();
        MediaType mediaType = typedVal.getDataType().getMediaType();
        boolean isJson = "json".equalsIgnoreCase(mediaType.getSubType()) || mediaType.getSubType().toLowerCase(Locale.ROOT).endsWith("+json");
        // customFields must always be parsed as JSON to be merged as structured fields
        boolean parseAsJson = isJson && (jsonOutput.isParseContentFieldsInJsonOutput() || CUSTOM_FIELDS.equals(field));

        if (parseAsJson) {
            JsonNode node;
            try {
                node = (value instanceof InputStream) ? mapper.readTree((InputStream) value) : mapper.readTree(asString(typedVal));
            } catch (Exception e) {
                LOGGER.debug("Content field '{}' declared as JSON but could not be parsed. Logging it as text", field, e);
                return mapper.getNodeFactory().textNode(truncate(asString(typedVal), jsonOutput.getMaxContentLength()));
            }
            JsonMasker masker = config.getContentMasker();
            if (masker != null && node != null) {
                node = masker.mask(node);
            }
            int maxLength = jsonOutput.getMaxContentLength();
            if (maxLength > 0 && node != null && !CUSTOM_FIELDS.equals(field)) {
                String serialized = mapper.writeValueAsString(node);
                if (serialized.length() > maxLength) {
                    return mapper.getNodeFactory().textNode(truncate(serialized, maxLength));
                }
            }
            return node;
        }
        return mapper.getNodeFactory().textNode(truncate(asString(typedVal), jsonOutput.getMaxContentLength()));
    }

    private String asString(TypedValue<?> typedVal) {
        Object value = typedVal.getValue();
        if (value instanceof String) {
            return (String) value;
        }
        return (String) transformationService.transform(value, typedVal.getDataType(), TEXT_STRING);
    }

    static String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + TRUNCATED_SUFFIX;
    }

    private void mergeCustomFields(ObjectNode target, JsonNode customFields) {
        if (customFields == null) {
            return;
        }
        if (!customFields.isObject()) {
            target.set(CUSTOM_FIELDS, customFields);
            return;
        }
        customFields.fields().forEachRemaining(e -> {
            if (RESERVED_FIELDS.contains(e.getKey()) || target.has(e.getKey())) {
                LOGGER.debug("Custom field '{}' collides with a standard field and was ignored", e.getKey());
            } else {
                target.set(e.getKey(), e.getValue());
            }
        });
    }

    private void addRuntimeContext(ObjectNode node, JsonOutput jsonOutput) {
        node.put("threadName", Thread.currentThread().getName());
        if (jsonOutput.isLogHostInfo()) {
            ContextEnricher.addHostInfo(node);
        }
        if (jsonOutput.isLogTraceContext()) {
            ContextEnricher.addTraceContext(node);
        }
        if (jsonOutput.isLogMdcFields()) {
            ContextEnricher.addMdcContext(node);
        }
    }

    private long computeElapsed(JsonloggerConfiguration config, CorrelationInfo correlationInfo, FlowListener flowListener, long loggerTimestamp) {
        long initialTimestamp = loggerTimestamp;
        try {
            // Add cache entry for initial timestamp based on correlation id
            initialTimestamp = config.getCachedTimerTimestamp(correlationInfo.getCorrelationId(), loggerTimestamp);
        } catch (Exception e) {
            LOGGER.error("initialTimestamp could not be retrieved from the cache config. Defaulting to current System.currentTimeMillis()", e);
        }
        if (initialTimestamp == loggerTimestamp) {
            // First log for this correlation id (or same millisecond): make sure timer gets cleaned up once the flow completes
            flowListener.onComplete(new TimerRemoverRunnable(correlationInfo.getCorrelationId(), config));
        }
        return loggerTimestamp - initialTimestamp;
    }

    private boolean isSupportedCategory(JsonloggerConfiguration config, String category) {
        List<String> categories = config.getExternalDestination().getSupportedCategories();
        return categories == null || categories.isEmpty() || categories.contains(category);
    }

    private void publishScopeLogEvents(JsonloggerConfiguration config, Logger jsonLogger, String correlationId, String finalLogBefore, String finalLogAfter) {
        if (config.getExternalDestination() != null && isSupportedCategory(config, jsonLogger.getName())) {
            LOGGER.debug("{} is a supported category for external destination", jsonLogger.getName());
            // Publishing before and after logEvents for better efficiency
            logEvent.publishToExternalDestination(correlationId, finalLogBefore, config.getConfigName());
            logEvent.publishToExternalDestination(correlationId, finalLogAfter, config.getConfigName());
        }
    }

    private ObjectNode locationInfoToNode(ComponentLocation location) {
        ObjectNode locationInfo = om.getObjectMapper().createObjectNode();
        locationInfo.put("rootContainer", location.getRootContainerName());
        locationInfo.put("component", location.getComponentIdentifier().getIdentifier().toString());
        locationInfo.put("fileName", location.getFileName().orElse(""));
        locationInfo.put("lineInFile", location.getLineInFile().map(String::valueOf).orElse(""));
        return locationInfo;
    }

    private String printObjectToLog(Logger jsonLogger, ObjectNode loggerObj, String priority, boolean isPrettyPrint) {
        ObjectWriter ow = isPrettyPrint ? om.getObjectMapper().writerWithDefaultPrettyPrinter() : om.getObjectMapper().writer();
        String logLine = "";
        try {
            logLine = ow.writeValueAsString(loggerObj);
        } catch (Exception e) {
            LOGGER.error("Error parsing log data as a string", e);
        }
        doLog(jsonLogger, priority, logLine);
        return logLine;
    }

    private static void doLog(Logger jsonLogger, String priority, String logLine) {
        switch (priority) {
            case "TRACE":
                jsonLogger.trace(logLine);
                break;
            case "DEBUG":
                jsonLogger.debug(logLine);
                break;
            case "INFO":
                jsonLogger.info(logLine);
                break;
            case "WARN":
                jsonLogger.warn(logLine);
                break;
            case "ERROR":
                jsonLogger.error(logLine);
                break;
            default:
                jsonLogger.info(logLine);
        }
    }

    private static boolean isLogEnabled(Logger jsonLogger, String priority) {
        switch (priority) {
            case "TRACE":
                return jsonLogger.isTraceEnabled();
            case "DEBUG":
                return jsonLogger.isDebugEnabled();
            case "INFO":
                return jsonLogger.isInfoEnabled();
            case "WARN":
                return jsonLogger.isWarnEnabled();
            case "ERROR":
                return jsonLogger.isErrorEnabled();
            default:
                return false;
        }
    }

    static Logger getLogger(String category) {
        return LoggerFactory.getLogger((category != null && !category.trim().isEmpty()) ? category : DEFAULT_CATEGORY);
    }

    private static void setPropertyQuietly(Object bean, String property, Object value) {
        try {
            BeanUtils.setProperty(bean, property, value);
        } catch (Exception e) {
            LOGGER.error("Failed setting field: " + property, e);
        }
    }

    // Allows executing timer cleanup on flowListener onComplete events
    private static class TimerRemoverRunnable implements Runnable {

        private final String key;
        private final JsonloggerConfiguration config;

        TimerRemoverRunnable(String key, JsonloggerConfiguration config) {
            this.key = key;
            this.config = config;
        }

        @Override
        public void run() {
            LOGGER.debug("Removing key: {}", key);
            config.removeCachedTimerTimestamp(key);
        }
    }
}
