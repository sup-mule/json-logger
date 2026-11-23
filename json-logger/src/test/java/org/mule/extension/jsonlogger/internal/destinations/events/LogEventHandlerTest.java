package org.mule.extension.jsonlogger.internal.destinations.events;

import org.junit.Before;
import org.junit.Test;
import org.mule.extension.jsonlogger.internal.destinations.Destination;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class LogEventHandlerTest {

    private Destination jms;
    private Destination amq;
    private LogEventHandler handler;

    @Before
    public void setUp() {
        jms = mock(Destination.class);
        when(jms.getMaxBatchSize()).thenReturn(3);
        amq = mock(Destination.class);
        when(amq.getMaxBatchSize()).thenReturn(10);
        Map<String, Destination> destinations = new HashMap<>();
        destinations.put("jmsConfig", jms);
        destinations.put("amqConfig", amq);
        handler = new LogEventHandler(destinations);
    }

    private static LogEvent event(String log, String config) {
        LogEvent e = new LogEvent();
        e.setLogEvent("corr", log, config);
        return e;
    }

    @Test
    public void flushesWhenMaxBatchSizeIsReached() {
        handler.onEvent(event("{\"n\":1}", "jmsConfig"), 0, false);
        handler.onEvent(event("{\"n\":2}", "jmsConfig"), 1, false);
        verify(jms, never()).sendToExternalDestination(anyString());

        handler.onEvent(event("{\"n\":3}", "jmsConfig"), 2, false);
        verify(jms).sendToExternalDestination("[{\"n\":1}, {\"n\":2}, {\"n\":3}]");

        // buffer was cleared: next batch starts empty
        handler.onEvent(event("{\"n\":4}", "jmsConfig"), 3, true);
        verify(jms).sendToExternalDestination("[{\"n\":4}]");
    }

    @Test
    public void endOfBatchFlushesEveryConfigIndependently() {
        handler.onEvent(event("a", "jmsConfig"), 0, false);
        handler.onEvent(event("b", "amqConfig"), 1, false);
        handler.onEvent(event("c", "amqConfig"), 2, true);

        verify(jms).sendToExternalDestination("[a]");
        verify(amq).sendToExternalDestination("[b, c]");
    }

    @Test
    public void flushAllSkipsEmptyBuffers() {
        handler.onEvent(event("a", "jmsConfig"), 0, true);
        reset(jms);

        handler.flushAllLogs();

        verify(jms, never()).sendToExternalDestination(anyString());
        verify(amq, never()).sendToExternalDestination(anyString());
    }

    @Test
    public void destinationFailureDoesNotPropagateAndBufferIsCleared() {
        doThrow(new RuntimeException("broker down")).when(jms).sendToExternalDestination(anyString());

        handler.onEvent(event("a", "jmsConfig"), 0, true);
        doNothing().when(jms).sendToExternalDestination(anyString());
        handler.onEvent(event("b", "jmsConfig"), 1, true);

        verify(jms).sendToExternalDestination("[b]");
    }

    @Test
    public void factoryCreatesEventsThatCanBePopulated() {
        LogEvent e = new LogEventFactory().newInstance();
        e.setLogEvent("c1", "log", "cfg");
        org.junit.Assert.assertEquals("c1", e.getCorrelationId());
        org.junit.Assert.assertEquals("log", e.getLog());
        org.junit.Assert.assertEquals("cfg", e.getConfigName());
    }
}
