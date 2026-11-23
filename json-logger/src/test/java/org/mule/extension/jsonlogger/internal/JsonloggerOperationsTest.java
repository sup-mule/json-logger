package org.mule.extension.jsonlogger.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.Level;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mule.extension.jsonlogger.api.pojos.GlobalSettings;
import org.mule.extension.jsonlogger.api.pojos.JsonOutput;
import org.mule.extension.jsonlogger.api.pojos.LoggerProcessor;
import org.mule.extension.jsonlogger.api.pojos.MaskingStrategy;
import org.mule.extension.jsonlogger.api.pojos.Priority;
import org.mule.extension.jsonlogger.api.pojos.ScopeTracePoint;
import org.mule.extension.jsonlogger.api.pojos.TracePoint;
import org.mule.extension.jsonlogger.internal.destinations.Destination;
import org.mule.extension.jsonlogger.internal.singleton.ConfigsSingleton;
import org.mule.extension.jsonlogger.internal.singleton.LogEventSingleton;
import org.mule.extension.jsonlogger.internal.singleton.ObjectMapperSingleton;
import org.mule.runtime.api.component.ComponentIdentifier;
import org.mule.runtime.api.component.TypedComponentIdentifier;
import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.metadata.DataType;
import org.mule.runtime.api.metadata.MediaType;
import org.mule.runtime.api.metadata.TypedValue;
import org.mule.runtime.api.transformation.TransformationService;
import org.mule.runtime.extension.api.runtime.operation.FlowListener;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.runtime.extension.api.runtime.parameter.CorrelationInfo;
import org.mule.runtime.extension.api.runtime.parameter.ParameterResolver;
import org.mule.runtime.extension.api.runtime.process.CompletionCallback;
import org.mule.runtime.extension.api.runtime.route.Chain;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
public class JsonloggerOperationsTest {

    private static final String CONFIG_NAME = "JSON_Logger_Config";
    private static final String CORRELATION_ID = "corr-123";

    @Rule
    public LogCapture capture = new LogCapture(Level.INFO);

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonloggerOperations ops;
    private ConfigsSingleton configs;
    private LogEventSingleton logEvent;
    private TransformationService transformationService;
    private CorrelationInfo correlationInfo;
    private ComponentLocation location;
    private FlowListener flowListener;
    private CompletionCallback<Void, Void> callback;

    @Before
    public void setUp() throws Exception {
        configs = new ConfigsSingleton();
        logEvent = mock(LogEventSingleton.class);
        transformationService = mock(TransformationService.class);

        ops = new JsonloggerOperations();
        ops.om = new ObjectMapperSingleton();
        ops.logEvent = logEvent;
        ops.configs = configs;
        setField(ops, "transformationService", transformationService);

        correlationInfo = mock(CorrelationInfo.class);
        when(correlationInfo.getCorrelationId()).thenReturn(CORRELATION_ID);
        when(correlationInfo.getEventId()).thenReturn("event-1");

        TypedComponentIdentifier identifier = mock(TypedComponentIdentifier.class);
        when(identifier.getIdentifier()).thenReturn(ComponentIdentifier.buildFromStringRepresentation("json-logger:logger"));
        location = mock(ComponentLocation.class);
        when(location.getRootContainerName()).thenReturn("my-flow");
        when(location.getComponentIdentifier()).thenReturn(identifier);
        when(location.getFileName()).thenReturn(Optional.of("app.xml"));
        when(location.getLineInFile()).thenReturn(Optional.of(42));
        when(location.getLocation()).thenReturn("my-flow/processors/0");

        flowListener = mock(FlowListener.class);
        callback = mock(CompletionCallback.class);
    }

    /* ===================================================================== */
    /* Fixtures                                                              */
    /* ===================================================================== */

    private JsonOutput defaultOutput() {
        JsonOutput out = new JsonOutput();
        out.setLogLocationInfo(true);
        out.setParseContentFieldsInJsonOutput(true);
        return out;
    }

    private JsonloggerConfiguration config(JsonOutput out) throws Exception {
        return config(out, null);
    }

    private JsonloggerConfiguration config(JsonOutput out, Destination destination) throws Exception {
        JsonloggerConfiguration config = new JsonloggerConfiguration();
        config.configsSingleton = configs;
        setField(config, "configName", CONFIG_NAME);
        GlobalSettings gs = new GlobalSettings();
        gs.setApplicationName("orders-api");
        gs.setApplicationVersion("1.2.3");
        gs.setEnvironment("dev");
        config.setGlobalSettings(gs);
        config.setJsonOutput(out);
        config.setExternalDestination(destination);
        config.initialise();
        return config;
    }

    private LoggerProcessor processor(String message) {
        LoggerProcessor lp = new LoggerProcessor();
        lp.setCorrelationId(CORRELATION_ID);
        lp.setMessage(message);
        lp.setTracePoint(TracePoint.START);
        lp.setPriority(Priority.INFO);
        lp.setCategory(capture.category());
        return lp;
    }

    private static ParameterResolver<TypedValue<InputStream>> resolver(Object value, DataType dataType) {
        ParameterResolver resolver = mock(ParameterResolver.class);
        when(resolver.resolve()).thenReturn(new TypedValue<>(value, dataType));
        return resolver;
    }

    private static ParameterResolver<TypedValue<InputStream>> json(String value) {
        return resolver(value, DataType.JSON_STRING);
    }

    private static ParameterResolver<TypedValue<InputStream>> text(String value) {
        return resolver(value, DataType.TEXT_STRING);
    }

    private JsonNode log(LoggerProcessor lp, JsonloggerConfiguration config) throws Exception {
        ops.logger(lp, correlationInfo, location, config, flowListener, callback);
        verify(callback).success(any());
        assertEquals("Expected exactly one log line", 1, capture.messages().size());
        return mapper.readTree(capture.messages().get(0));
    }

    private List<JsonNode> logLines() throws Exception {
        List<JsonNode> nodes = new ArrayList<>();
        for (String m : capture.messages()) {
            nodes.add(mapper.readTree(m));
        }
        return nodes;
    }

    static void setField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /* ===================================================================== */
    /* logger operation                                                      */
    /* ===================================================================== */

    @Test
    public void logsStandardFieldsInExpectedOrder() throws Exception {
        JsonNode out = log(processor("hello"), config(defaultOutput()));

        assertEquals(CORRELATION_ID, out.get("correlationId").asText());
        assertEquals("hello", out.get("message").asText());
        assertEquals("START", out.get("tracePoint").asText());
        assertEquals("INFO", out.get("priority").asText());
        assertEquals(capture.category(), out.get("category").asText());
        assertEquals(0, out.get("elapsed").asLong());
        assertTrue(out.get("timestamp").asText().matches("\\d{4}-\\d{2}-\\d{2}T.*"));
        assertEquals("orders-api", out.get("applicationName").asText());
        assertEquals("1.2.3", out.get("applicationVersion").asText());
        assertEquals("dev", out.get("environment").asText());
        assertEquals(Thread.currentThread().getName(), out.get("threadName").asText());

        JsonNode loc = out.get("locationInfo");
        assertEquals("my-flow", loc.get("rootContainer").asText());
        assertEquals("json-logger:logger", loc.get("component").asText());
        assertEquals("app.xml", loc.get("fileName").asText());
        assertEquals("42", loc.get("lineInFile").asText());

        List<String> names = new ArrayList<>();
        out.fieldNames().forEachRemaining(names::add);
        assertTrue("elapsed should come before timestamp", names.indexOf("elapsed") < names.indexOf("timestamp"));
        assertTrue("timestamp should come before global settings", names.indexOf("timestamp") < names.indexOf("applicationName"));
        assertFalse("customFields must never be output as-is", out.has("customFields"));
        assertFalse(out.has("additionalProperties"));
    }

    @Test
    public void singleLineByDefaultAndMultiLineWithPrettyPrint() throws Exception {
        ops.logger(processor("a"), correlationInfo, location, config(defaultOutput()), flowListener, callback);
        JsonOutput pretty = defaultOutput();
        pretty.setPrettyPrint(true);
        ops.logger(processor("b"), correlationInfo, location, config(pretty), flowListener, callback);

        assertFalse(capture.messages().get(0).contains("\n"));
        assertTrue(capture.messages().get(1).contains("\n"));
    }

    @Test
    public void locationInfoCanBeTurnedOff() throws Exception {
        JsonOutput out = defaultOutput();
        out.setLogLocationInfo(false);
        assertFalse(log(processor("x"), config(out)).has("locationInfo"));
    }

    @Test
    public void skipsAllWorkWhenPriorityIsNotEnabled() throws Exception {
        LoggerProcessor lp = processor("debug only");
        lp.setPriority(Priority.DEBUG);
        ParameterResolver<TypedValue<InputStream>> content = json("{\"a\":1}");
        lp.setContent(content);

        ops.logger(lp, correlationInfo, location, config(defaultOutput()), flowListener, callback);

        verify(callback).success(any());
        assertTrue(capture.messages().isEmpty());
        verify(content, never()).resolve();
    }

    @Test
    public void logsAtTheRequestedLevel() throws Exception {
        JsonloggerConfiguration config = config(defaultOutput());
        for (Priority p : new Priority[]{Priority.INFO, Priority.WARN, Priority.ERROR}) {
            LoggerProcessor lp = processor(p.toString());
            lp.setPriority(p);
            ops.logger(lp, correlationInfo, location, config, flowListener, callback);
        }
        assertEquals(Arrays.asList(Level.INFO, Level.WARN, Level.ERROR), capture.levels());
    }

    @Test
    public void jsonContentIsParsedIntoStructuredOutput() throws Exception {
        LoggerProcessor lp = processor("with content");
        lp.setContent(json("{\"orderId\":7,\"items\":[\"a\",\"b\"]}"));

        JsonNode content = log(lp, config(defaultOutput())).get("content");

        assertTrue(content.isObject());
        assertEquals(7, content.get("orderId").asInt());
        assertEquals("b", content.at("/items/1").asText());
    }

    @Test
    public void jsonContentIsKeptAsStringWhenParsingDisabled() throws Exception {
        JsonOutput out = defaultOutput();
        out.setParseContentFieldsInJsonOutput(false);
        LoggerProcessor lp = processor("with content");
        lp.setContent(json("{\"orderId\":7}"));

        JsonNode content = log(lp, config(out)).get("content");

        assertTrue(content.isTextual());
        assertEquals("{\"orderId\":7}", content.asText());
    }

    @Test
    public void structuredSuffixMediaTypesAreTreatedAsJson() throws Exception {
        DataType problemJson = DataType.builder().type(String.class).mediaType("application/problem+json").build();
        LoggerProcessor lp = processor("problem");
        lp.setError(resolver("{\"status\":500}", problemJson));

        assertEquals(500, log(lp, config(defaultOutput())).at("/error/status").asInt());
    }

    @Test
    public void inputStreamJsonContentIsParsed() throws Exception {
        LoggerProcessor lp = processor("stream");
        lp.setContent(resolver(new ByteArrayInputStream("{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8)),
                DataType.builder().type(InputStream.class).mediaType(MediaType.APPLICATION_JSON).build()));

        assertEquals("v", log(lp, config(defaultOutput())).at("/content/k").asText());
    }

    @Test
    public void nonStringNonJsonContentIsConvertedWithTransformationService() throws Exception {
        byte[] bytes = "plain bytes".getBytes(StandardCharsets.UTF_8);
        DataType binary = DataType.builder().type(byte[].class).mediaType(MediaType.BINARY).build();
        when(transformationService.transform(eq(bytes), any(DataType.class), eq(DataType.TEXT_STRING))).thenReturn("plain bytes");
        LoggerProcessor lp = processor("bytes");
        lp.setContent(resolver(bytes, binary));

        assertEquals("plain bytes", log(lp, config(defaultOutput())).get("content").asText());
    }

    @Test
    public void malformedJsonContentFallsBackToText() throws Exception {
        LoggerProcessor lp = processor("bad json");
        lp.setContent(json("{not json"));

        JsonNode content = log(lp, config(defaultOutput())).get("content");

        assertTrue(content.isTextual());
        assertEquals("{not json", content.asText());
    }

    @Test
    public void nullContentValueIsOmitted() throws Exception {
        LoggerProcessor lp = processor("null content");
        lp.setContent(resolver(null, DataType.JSON_STRING));

        assertFalse(log(lp, config(defaultOutput())).has("content"));
    }

    @Test
    public void failingExpressionDoesNotBreakTheLogEntry() throws Exception {
        ParameterResolver failing = mock(ParameterResolver.class);
        when(failing.resolve()).thenThrow(new RuntimeException("boom"));
        LoggerProcessor lp = processor("still logged");
        lp.setContent(failing);

        JsonNode out = log(lp, config(defaultOutput()));

        assertEquals("still logged", out.get("message").asText());
        assertEquals("Error parsing expression. See logs for details.", out.get("content").asText());
    }

    @Test
    public void contentFieldsAreMaskedUsingConfiguredStrategy() throws Exception {
        JsonOutput out = defaultOutput();
        out.setContentFieldsDataMasking("password, $.card.number");
        out.setMaskingStrategy(MaskingStrategy.FULL);
        LoggerProcessor lp = processor("masked");
        lp.setContent(json("{\"user\":\"bob\",\"password\":\"s3cret\",\"card\":{\"number\":\"4111\",\"type\":\"visa\"}}"));

        JsonNode content = log(lp, config(out)).get("content");

        assertEquals("bob", content.get("user").asText());
        assertEquals("****", content.get("password").asText());
        assertEquals("****", content.at("/card/number").asText());
        assertEquals("visa", content.at("/card/type").asText());
    }

    @Test
    public void textContentIsTruncatedToMaxLength() throws Exception {
        JsonOutput out = defaultOutput();
        out.setMaxContentLength(5);
        LoggerProcessor lp = processor("truncate");
        lp.setContent(text("0123456789"));

        assertEquals("01234" + JsonloggerOperations.TRUNCATED_SUFFIX, log(lp, config(out)).get("content").asText());
    }

    @Test
    public void oversizedJsonContentIsTruncatedToText() throws Exception {
        JsonOutput out = defaultOutput();
        out.setMaxContentLength(10);
        LoggerProcessor lp = processor("truncate");
        lp.setContent(json("{\"payload\":\"a very long value\"}"));

        JsonNode content = log(lp, config(out)).get("content");

        assertTrue(content.isTextual());
        assertEquals("{\"payload\"" + JsonloggerOperations.TRUNCATED_SUFFIX, content.asText());
    }

    @Test
    public void jsonContentWithinMaxLengthIsKeptStructured() throws Exception {
        JsonOutput out = defaultOutput();
        out.setMaxContentLength(1000);
        LoggerProcessor lp = processor("fits");
        lp.setContent(json("{\"a\":1}"));

        assertTrue(log(lp, config(out)).get("content").isObject());
    }

    @Test
    public void customFieldsAreMergedAtTopLevelWithoutOverridingStandardFields() throws Exception {
        LoggerProcessor lp = processor("custom");
        lp.setCustomFields(json("{\"orderId\":\"o-1\",\"correlationId\":\"hijack\",\"environment\":\"prod\",\"message\":\"x\",\"nested\":{\"a\":1}}"));

        JsonNode out = log(lp, config(defaultOutput()));

        assertEquals("o-1", out.get("orderId").asText());
        assertEquals(1, out.at("/nested/a").asInt());
        assertEquals(CORRELATION_ID, out.get("correlationId").asText());
        assertEquals("custom", out.get("message").asText());
        assertEquals("environment comes from global settings", "dev", out.get("environment").asText());
        assertFalse(out.has("customFields"));
    }

    @Test
    public void customFieldsAreParsedAsJsonEvenWhenContentParsingIsDisabled() throws Exception {
        JsonOutput out = defaultOutput();
        out.setParseContentFieldsInJsonOutput(false);
        LoggerProcessor lp = processor("custom");
        lp.setCustomFields(json("{\"orderId\":\"o-1\"}"));

        assertEquals("o-1", log(lp, config(out)).get("orderId").asText());
    }

    @Test
    public void customFieldsAreNotTruncated() throws Exception {
        JsonOutput out = defaultOutput();
        out.setMaxContentLength(5);
        LoggerProcessor lp = processor("custom");
        lp.setCustomFields(json("{\"orderId\":\"a-long-order-id\"}"));

        assertEquals("a-long-order-id", log(lp, config(out)).get("orderId").asText());
    }

    @Test
    public void nonObjectCustomFieldsAreNestedUnderCustomFields() throws Exception {
        LoggerProcessor lp = processor("custom");
        lp.setCustomFields(json("[1,2,3]"));

        JsonNode out = log(lp, config(defaultOutput()));

        assertTrue(out.get("customFields").isArray());
        assertEquals(3, out.get("customFields").size());
    }

    @Test
    public void disabledFieldsAreRemovedFromOutputAndNotEvaluated() throws Exception {
        JsonOutput out = defaultOutput();
        out.setDisabledFields("content, locationInfo ,threadName,applicationVersion");
        ParameterResolver<TypedValue<InputStream>> content = json("{\"a\":1}");
        LoggerProcessor lp = processor("disabled");
        lp.setContent(content);

        JsonNode node = log(lp, config(out));

        assertFalse(node.has("content"));
        assertFalse(node.has("locationInfo"));
        assertFalse(node.has("threadName"));
        assertFalse(node.has("applicationVersion"));
        assertTrue(node.has("applicationName"));
        verify(content, never()).resolve();
    }

    @Test
    public void redactionAppliesToValuesButNotToReservedFields() throws Exception {
        JsonOutput out = defaultOutput();
        out.setRedactionPatterns("EMAIL,ord-\\d+");
        LoggerProcessor lp = processor("contact bob@example.com");
        lp.setCorrelationId("ord-999");
        lp.setContent(json("{\"note\":\"mail alice@example.org about ord-123\"}"));

        JsonNode node = log(lp, config(out));

        assertEquals("contact [REDACTED]", node.get("message").asText());
        assertEquals("mail [REDACTED] about [REDACTED]", node.at("/content/note").asText());
        assertEquals("correlationId is never redacted", "ord-999", node.get("correlationId").asText());
    }

    @Test
    public void runtimeContextIsAddedWhenEnabled() throws Exception {
        JsonOutput out = defaultOutput();
        out.setLogTraceContext(true);
        out.setLogMdcFields(true);
        out.setLogHostInfo(true);
        org.slf4j.MDC.put("trace_id", "t-1");
        org.slf4j.MDC.put("span_id", "s-1");
        org.slf4j.MDC.put("tenant", "acme");
        try {
            JsonNode node = log(processor("ctx"), config(out));
            assertEquals("t-1", node.get("traceId").asText());
            assertEquals("s-1", node.get("spanId").asText());
            assertEquals("acme", node.at("/context/tenant").asText());
            assertFalse(node.at("/context").has("trace_id"));
            assertTrue(node.has("hostName"));
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    @Test
    public void runtimeContextIsNotAddedByDefault() throws Exception {
        org.slf4j.MDC.put("trace_id", "t-1");
        try {
            JsonNode node = log(processor("ctx"), config(defaultOutput()));
            assertFalse(node.has("traceId"));
            assertFalse(node.has("context"));
            assertFalse(node.has("hostName"));
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    @Test
    public void elapsedIsMeasuredFromFirstLogOfTheCorrelationId() throws Exception {
        JsonloggerConfiguration config = config(defaultOutput());
        config.getTimers().put(CORRELATION_ID, System.currentTimeMillis() - 5_000);

        long elapsed = log(processor("later"), config).get("elapsed").asLong();

        assertTrue("elapsed was " + elapsed, elapsed >= 5_000 && elapsed < 60_000);
        verify(flowListener, never()).onComplete(any());
    }

    @Test
    public void firstLogRegistersTimerCleanupOnFlowCompletion() throws Exception {
        JsonloggerConfiguration config = config(defaultOutput());

        ops.logger(processor("first"), correlationInfo, location, config, flowListener, callback);

        assertTrue(config.getTimers().containsKey(CORRELATION_ID));
        ArgumentCaptor<Runnable> cleanup = ArgumentCaptor.forClass(Runnable.class);
        verify(flowListener).onComplete(cleanup.capture());
        cleanup.getValue().run();
        assertFalse(config.getTimers().containsKey(CORRELATION_ID));
    }

    @Test
    public void timerCleanupIsRegisteredEvenWhenPriorityIsDisabled() throws Exception {
        LoggerProcessor lp = processor("debug");
        lp.setPriority(Priority.DEBUG);
        JsonloggerConfiguration config = config(defaultOutput());

        ops.logger(lp, correlationInfo, location, config, flowListener, callback);

        verify(flowListener).onComplete(any());
    }

    @Test
    public void publishesToExternalDestinationWhenAllCategoriesSupported() throws Exception {
        Destination destination = mock(Destination.class);
        when(destination.getSupportedCategories()).thenReturn(new ArrayList<>());

        ops.logger(processor("forward"), correlationInfo, location, config(defaultOutput(), destination), flowListener, callback);

        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(logEvent).publishToExternalDestination(eq("event-1"), line.capture(), eq(CONFIG_NAME));
        assertEquals(capture.messages().get(0), line.getValue());
    }

    @Test
    public void publishesOnlyForListedCategories() throws Exception {
        Destination destination = mock(Destination.class);
        when(destination.getSupportedCategories()).thenReturn(new ArrayList<>(Arrays.asList("some.other.category")));
        JsonloggerConfiguration config = config(defaultOutput(), destination);

        ops.logger(processor("not forwarded"), correlationInfo, location, config, flowListener, callback);
        verify(logEvent, never()).publishToExternalDestination(anyString(), anyString(), anyString());

        when(destination.getSupportedCategories()).thenReturn(new ArrayList<>(Arrays.asList(capture.category())));
        ops.logger(processor("forwarded"), correlationInfo, location, config, flowListener, callback);
        verify(logEvent).publishToExternalDestination(anyString(), anyString(), eq(CONFIG_NAME));
    }

    @Test
    public void unexpectedFailureNeverBreaksTheFlow() throws Exception {
        JsonloggerConfiguration config = config(defaultOutput());
        config.setGlobalSettings(null); // valueToTree(null) yields NullNode -> ClassCastException inside the operation

        ops.logger(processor("x"), correlationInfo, location, config, flowListener, callback);

        verify(callback).success(any());
        verify(callback, never()).error(any());
    }

    @Test
    public void defaultCategoryIsUsedWhenBlank() {
        assertEquals(JsonloggerOperations.DEFAULT_CATEGORY, JsonloggerOperations.getLogger(null).getName());
        assertEquals(JsonloggerOperations.DEFAULT_CATEGORY, JsonloggerOperations.getLogger("  ").getName());
        assertEquals("my.cat", JsonloggerOperations.getLogger("my.cat").getName());
    }

    /* ===================================================================== */
    /* loggerScope operation                                                 */
    /* ===================================================================== */

    private Chain successfulChain(Result result) {
        Chain chain = mock(Chain.class);
        doAnswer(inv -> {
            ((Consumer<Result>) inv.getArgument(0)).accept(result);
            return null;
        }).when(chain).process(any(Consumer.class), any(BiConsumer.class));
        return chain;
    }

    private Chain failingChain(Throwable error) {
        Chain chain = mock(Chain.class);
        doAnswer(inv -> {
            ((BiConsumer<Throwable, Result>) inv.getArgument(1)).accept(error, null);
            return null;
        }).when(chain).process(any(Consumer.class), any(BiConsumer.class));
        return chain;
    }

    private void runScope(String configRef, Priority priority, String message, Chain chain, CompletionCallback<Object, Object> cb) {
        ops.loggerScope(configRef, priority, ScopeTracePoint.OUTBOUND_REQUEST_SCOPE, capture.category(), message,
                CORRELATION_ID, location, correlationInfo, flowListener, chain, cb);
    }

    @Test
    public void scopeLogsBeforeAndAfterAndPropagatesResult() throws Exception {
        config(defaultOutput());
        Result result = Result.builder().output("payload").build();
        CompletionCallback<Object, Object> cb = mock(CompletionCallback.class);

        runScope(CONFIG_NAME, Priority.INFO, "Calling Salesforce", successfulChain(result), cb);

        verify(cb).success(result);
        List<JsonNode> lines = logLines();
        assertEquals(2, lines.size());
        JsonNode before = lines.get(0);
        JsonNode after = lines.get(1);
        assertEquals("OUTBOUND_REQUEST_SCOPE_BEFORE", before.get("tracePoint").asText());
        assertEquals("OUTBOUND_REQUEST_SCOPE_AFTER", after.get("tracePoint").asText());
        assertEquals("Calling Salesforce", before.get("message").asText());
        assertEquals(CORRELATION_ID, before.get("correlationId").asText());
        assertEquals(0, before.get("scopeElapsed").asLong());
        assertTrue(after.get("scopeElapsed").asLong() >= 0);
        assertEquals("orders-api", after.get("applicationName").asText());
        assertEquals("my-flow", before.at("/locationInfo/rootContainer").asText());
    }

    @Test
    public void scopeLogsErrorAndPropagatesFailure() throws Exception {
        JsonOutput out = defaultOutput();
        out.setRedactionPatterns("EMAIL");
        config(out);
        RuntimeException failure = new IllegalStateException("cannot reach bob@example.com");
        CompletionCallback<Object, Object> cb = mock(CompletionCallback.class);

        runScope(CONFIG_NAME, Priority.INFO, null, failingChain(failure), cb);

        verify(cb).error(failure);
        verify(cb, never()).success(any());
        List<JsonNode> lines = logLines();
        assertEquals(2, lines.size());
        assertFalse(lines.get(0).has("message"));
        JsonNode err = lines.get(1);
        assertEquals("EXCEPTION_SCOPE", err.get("tracePoint").asText());
        assertEquals("ERROR", err.get("priority").asText());
        assertEquals(IllegalStateException.class.getName(), err.at("/error/type").asText());
        assertEquals("cannot reach [REDACTED]", err.at("/error/description").asText());
        assertEquals("Error found: cannot reach [REDACTED]", err.get("message").asText());
        assertEquals(Level.ERROR, capture.levels().get(1));
    }

    @Test
    public void scopeWithUnknownConfigStillExecutesTheChain() {
        Result result = Result.builder().build();
        CompletionCallback<Object, Object> cb = mock(CompletionCallback.class);

        runScope("does-not-exist", Priority.INFO, null, successfulChain(result), cb);

        verify(cb).success(result);
        assertTrue(capture.messages().isEmpty());
    }

    @Test
    public void scopeWithUnknownConfigPropagatesChainErrors() {
        RuntimeException failure = new RuntimeException("x");
        CompletionCallback<Object, Object> cb = mock(CompletionCallback.class);

        runScope("does-not-exist", Priority.INFO, null, failingChain(failure), cb);

        verify(cb).error(failure);
    }

    @Test
    public void scopeWithDisabledPriorityExecutesChainWithoutLogging() throws Exception {
        config(defaultOutput());
        Result result = Result.builder().build();
        CompletionCallback<Object, Object> cb = mock(CompletionCallback.class);

        runScope(CONFIG_NAME, Priority.DEBUG, null, successfulChain(result), cb);

        verify(cb).success(result);
        assertTrue(capture.messages().isEmpty());
    }

    @Test
    public void scopeHonoursDisabledFields() throws Exception {
        JsonOutput out = defaultOutput();
        out.setDisabledFields("locationInfo,threadName");
        config(out);

        runScope(CONFIG_NAME, Priority.INFO, null, successfulChain(Result.builder().build()), mock(CompletionCallback.class));

        for (JsonNode line : logLines()) {
            assertFalse(line.has("locationInfo"));
            assertFalse(line.has("threadName"));
        }
    }

    @Test
    public void scopePublishesBeforeAndAfterToExternalDestination() throws Exception {
        Destination destination = mock(Destination.class);
        config(defaultOutput(), destination);

        runScope(CONFIG_NAME, Priority.INFO, null, successfulChain(Result.builder().build()), mock(CompletionCallback.class));

        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        verify(logEvent, times(2)).publishToExternalDestination(eq(CORRELATION_ID), lines.capture(), eq(CONFIG_NAME));
        assertEquals(capture.messages(), lines.getAllValues());
    }

    @Test
    public void scopePublishesBeforeAndErrorToExternalDestination() throws Exception {
        Destination destination = mock(Destination.class);
        config(defaultOutput(), destination);

        runScope(CONFIG_NAME, Priority.INFO, null, failingChain(new RuntimeException("x")), mock(CompletionCallback.class));

        verify(logEvent, times(2)).publishToExternalDestination(eq(CORRELATION_ID), anyString(), eq(CONFIG_NAME));
    }

    /* ===================================================================== */
    /* configuration                                                         */
    /* ===================================================================== */

    @Test
    public void initialiseRegistersConfigAndLifecyclesDestination() throws Exception {
        Destination destination = mock(Destination.class);
        JsonloggerConfiguration config = config(defaultOutput(), destination);

        assertSame(config, configs.getConfig(CONFIG_NAME));
        assertEquals(CONFIG_NAME, config.getConfigName());
        verify(destination).initialise();
        config.dispose();
        verify(destination).dispose();
    }

    @Test
    public void initialiseWithoutRulesLeavesMaskerAndRedactorUnset() throws Exception {
        JsonloggerConfiguration config = config(defaultOutput());

        assertNull(config.getContentMasker());
        assertNull(config.getRedactor());
        assertTrue(config.getDisabledFieldsList().isEmpty());
        config.dispose(); // no destination: must not fail
    }

    @Test
    public void initialiseFailsFastOnInvalidRedactionPattern() throws Exception {
        JsonOutput out = defaultOutput();
        out.setRedactionPatterns("[unclosed");
        try {
            config(out);
            fail("Expected InitialisationException");
        } catch (InitialisationException e) {
            assertTrue(e.getMessage().contains("Invalid redaction pattern"));
        }
    }

    @Test
    public void csvToListTrimsAndDropsEmptyEntries() {
        assertEquals(Arrays.asList("a", "b", "c"), JsonloggerConfiguration.csvToList(" a, ,b,,c "));
        assertTrue(JsonloggerConfiguration.csvToList(null).isEmpty());
        assertTrue(JsonloggerConfiguration.csvToList("   ").isEmpty());
    }

    @Test
    public void cachedTimerKeepsFirstTimestamp() throws Exception {
        JsonloggerConfiguration config = new JsonloggerConfiguration();
        assertEquals(Long.valueOf(100), config.getCachedTimerTimestamp("k", 100L));
        assertEquals(Long.valueOf(100), config.getCachedTimerTimestamp("k", 200L));
        config.removeCachedTimerTimestamp("k");
        assertEquals(Long.valueOf(300), config.getCachedTimerTimestamp("k", 300L));
    }
}
