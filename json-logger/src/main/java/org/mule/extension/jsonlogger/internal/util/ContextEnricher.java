package org.mule.extension.jsonlogger.internal.util;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.MDC;

import java.net.InetAddress;
import java.util.Map;
import java.util.TreeMap;

/**
 * Adds runtime context to a log entry: OpenTelemetry trace context, logging context (MDC) entries and host details.
 */
public final class ContextEnricher {

    // MDC keys populated by the Mule 4.9+ tracer (see feature flag mule.put.trace.id.and.span.id.in.mdc)
    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_SPAN_ID = "span_id";
    public static final String MDC_TRACE_FLAGS = "trace_flags";
    // MDC keys populated by the Mule runtime itself (already part of the log entry)
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_PROCESSOR_PATH = "processorPath";

    private static final String HOST_NAME = resolveHostName();
    private static final String WORKER_ID = firstNonBlank(System.getProperty("worker.id"), System.getProperty("mule.worker.id"));

    private ContextEnricher() {
    }

    public static void addTraceContext(ObjectNode node) {
        String traceId = MDC.get(MDC_TRACE_ID);
        if (traceId != null && !traceId.isEmpty()) {
            node.put("traceId", traceId);
            String spanId = MDC.get(MDC_SPAN_ID);
            if (spanId != null && !spanId.isEmpty()) {
                node.put("spanId", spanId);
            }
            String traceFlags = MDC.get(MDC_TRACE_FLAGS);
            if (traceFlags != null && !traceFlags.isEmpty()) {
                node.put("traceFlags", traceFlags);
            }
        }
    }

    /**
     * Adds every MDC entry (e.g. variables defined with the Tracing module "Set logging variable") under "context",
     * skipping entries that are already part of the standard output.
     */
    public static void addMdcContext(ObjectNode node) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        if (mdc == null || mdc.isEmpty()) {
            return;
        }
        Map<String, String> filtered = new TreeMap<>();
        mdc.forEach((k, v) -> {
            if (v != null && !isStandardKey(k)) {
                filtered.put(k, v);
            }
        });
        if (!filtered.isEmpty()) {
            ObjectNode ctx = node.putObject("context");
            filtered.forEach(ctx::put);
        }
    }

    public static void addHostInfo(ObjectNode node) {
        if (HOST_NAME != null) {
            node.put("hostName", HOST_NAME);
        }
        if (WORKER_ID != null) {
            node.put("workerId", WORKER_ID);
        }
    }

    private static boolean isStandardKey(String key) {
        return MDC_TRACE_ID.equals(key) || MDC_SPAN_ID.equals(key) || MDC_TRACE_FLAGS.equals(key)
                || MDC_CORRELATION_ID.equals(key) || MDC_PROCESSOR_PATH.equals(key);
    }

    private static String resolveHostName() {
        String fromEnv = firstNonBlank(System.getenv("HOSTNAME"), System.getenv("COMPUTERNAME"));
        if (fromEnv != null) {
            return fromEnv;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }
}
