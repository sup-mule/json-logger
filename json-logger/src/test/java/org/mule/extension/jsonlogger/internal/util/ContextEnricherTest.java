package org.mule.extension.jsonlogger.internal.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;

import static org.junit.Assert.*;

public class ContextEnricherTest {

    private final ObjectMapper om = new ObjectMapper();

    @After
    public void clear() {
        MDC.clear();
    }

    @Test
    public void addsTraceContextOnlyWhenPresent() {
        ObjectNode node = om.createObjectNode();
        ContextEnricher.addTraceContext(node);
        assertFalse(node.has("traceId"));

        MDC.put("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("span_id", "00f067aa0ba902b7");
        ContextEnricher.addTraceContext(node);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", node.get("traceId").asText());
        assertEquals("00f067aa0ba902b7", node.get("spanId").asText());
    }

    @Test
    public void addsCustomMdcEntriesExcludingStandardOnes() {
        MDC.put("correlationId", "abc");
        MDC.put("trace_id", "t");
        MDC.put("orderId", "42");
        ObjectNode node = om.createObjectNode();
        ContextEnricher.addMdcContext(node);
        assertEquals("42", node.at("/context/orderId").asText());
        assertFalse(node.get("context").has("correlationId"));
        assertFalse(node.get("context").has("trace_id"));
    }

    @Test
    public void noContextNodeWhenNothingToAdd() {
        MDC.put("correlationId", "abc");
        ObjectNode node = om.createObjectNode();
        ContextEnricher.addMdcContext(node);
        assertFalse(node.has("context"));
    }
}
