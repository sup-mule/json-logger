package org.mule.extension.jsonlogger.internal.singleton;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mule.extension.jsonlogger.internal.JsonloggerConfiguration;
import org.mule.extension.jsonlogger.internal.destinations.Destination;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Exercises the real LMAX disruptor pipeline: publish -> ring buffer -> handler -> destination.
 */
public class LogEventSingletonTest {

    private final List<String> sent = new CopyOnWriteArrayList<>();
    private Destination destination;
    private LogEventSingleton singleton;
    private boolean disposed;

    @Before
    public void setUp() throws Exception {
        destination = mock(Destination.class);
        when(destination.getMaxBatchSize()).thenReturn(1000);
        when(destination.getSelectedDestinationType()).thenReturn("MOCK");
        doAnswer(inv -> sent.add(inv.getArgument(0))).when(destination).sendToExternalDestination(anyString());

        JsonloggerConfiguration config = new JsonloggerConfiguration();
        config.setExternalDestination(destination);
        ConfigsSingleton configs = new ConfigsSingleton();
        configs.addConfig("cfg", config);

        singleton = new LogEventSingleton();
        singleton.configs = configs;
        singleton.initialise();
    }

    @After
    public void tearDown() {
        if (!disposed) {
            singleton.dispose();
        }
    }

    @Test
    public void publishedLogsAreDeliveredAsynchronously() {
        singleton.publishToExternalDestination("c1", "{\"m\":1}", "cfg");

        verify(destination, timeout(5000)).sendToExternalDestination(contains("{\"m\":1}"));
    }

    @Test
    public void disposeDrainsEveryPublishedLog() {
        for (int i = 0; i < 200; i++) {
            singleton.publishToExternalDestination("c" + i, "L" + i, "cfg");
        }
        singleton.dispose();
        disposed = true;

        long delivered = sent.stream()
                .map(batch -> batch.substring(1, batch.length() - 1).split(", "))
                .mapToLong(entries -> entries.length)
                .sum();
        assertEquals(200, delivered);
        String all = String.join(", ", sent);
        for (int i = 0; i < 200; i++) {
            org.junit.Assert.assertTrue("missing L" + i, all.matches("(?s).*\\bL" + i + "\\b.*"));
        }
    }
}
