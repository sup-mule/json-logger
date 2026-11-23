package org.mule.extension.jsonlogger.internal.destinations;

import com.mulesoft.mq.restclient.api.AnypointMQMessage;
import com.mulesoft.mq.restclient.api.CourierObservable;
import com.mulesoft.mq.restclient.api.CourierObserver;
import com.mulesoft.mq.restclient.api.DestinationLocation;
import com.mulesoft.mq.restclient.api.DestinationLocator;
import com.mulesoft.mq.restclient.api.MessageIdResult;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mule.runtime.api.metadata.TypedValue;
import org.mule.runtime.extension.api.client.ExtensionsClient;
import org.mule.runtime.extension.api.client.OperationParameters;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import org.mule.runtime.extension.internal.client.ComplexParameter;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
public class DestinationsTest {

    private static final String BATCH = "[{\"message\":\"hello\"}]";

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object body(OperationParameters params) {
        ComplexParameter mb = (ComplexParameter) params.get().get("messageBuilder");
        return ((TypedValue<?>) mb.getParameters().get("body")).getValue();
    }

    @Test
    public void jmsPublishesBatchToConfiguredQueue() throws Exception {
        JMSDestination jms = new JMSDestination();
        ExtensionsClient client = mock(ExtensionsClient.class);
        jms.extensionsClient = client;
        set(jms, "jmsConfigurationRef", "JMS_Config");
        set(jms, "queueDestination", "logger-queue");
        set(jms, "maxBatchSize", 25);
        set(jms, "logCategories", new ArrayList<>(Arrays.asList("a.b")));

        jms.sendToExternalDestination(BATCH);

        ArgumentCaptor<OperationParameters> params = ArgumentCaptor.forClass(OperationParameters.class);
        verify(client).executeAsync(eq("JMS"), eq("publish"), params.capture());
        assertEquals("JMS_Config", params.getValue().getConfigName().orElse(null));
        assertEquals("logger-queue", params.getValue().get().get("destination"));
        assertEquals(BATCH, body(params.getValue()));
        assertEquals(org.mule.extensions.jms.api.message.JmsMessageBuilder.class,
                ((ComplexParameter) params.getValue().get().get("messageBuilder")).getType());
        assertEquals("JMS", jms.getSelectedDestinationType());
        assertEquals(25, jms.getMaxBatchSize());
        assertEquals(Arrays.asList("a.b"), jms.getSupportedCategories());
    }

    @Test
    public void amqpPublishesBatchToConfiguredExchange() throws Exception {
        AMQPDestination amqp = new AMQPDestination();
        ExtensionsClient client = mock(ExtensionsClient.class);
        amqp.extensionsClient = client;
        set(amqp, "amqpConfigurationRef", "AMQP_Config");
        set(amqp, "exchangeDestination", "logger-exchange");
        set(amqp, "maxBatchSize", 10);

        amqp.sendToExternalDestination(BATCH);

        ArgumentCaptor<OperationParameters> params = ArgumentCaptor.forClass(OperationParameters.class);
        verify(client).executeAsync(eq("AMQP"), eq("publish"), params.capture());
        assertEquals("AMQP_Config", params.getValue().getConfigName().orElse(null));
        assertEquals("logger-exchange", params.getValue().get().get("exchangeName"));
        assertEquals(BATCH, body(params.getValue()));
        assertEquals("AMQP", amqp.getSelectedDestinationType());
        assertEquals(10, amqp.getMaxBatchSize());
    }

    @Test
    public void connectorFailuresAreSwallowed() {
        ExtensionsClient client = mock(ExtensionsClient.class);
        when(client.executeAsync(anyString(), anyString(), any())).thenThrow(new RuntimeException("not deployed"));
        JMSDestination jms = new JMSDestination();
        jms.extensionsClient = client;
        AMQPDestination amqp = new AMQPDestination();
        amqp.extensionsClient = client;

        jms.sendToExternalDestination(BATCH);   // must not throw
        amqp.sendToExternalDestination(BATCH);  // must not throw
    }

    @Test
    public void amqSendsJsonMessageToLocatedDestination() throws Exception {
        AMQDestination amq = new AMQDestination();
        DestinationLocator locator = mock(DestinationLocator.class);
        DestinationLocation location = mock(DestinationLocation.class);
        com.mulesoft.mq.restclient.api.Destination dest = mock(com.mulesoft.mq.restclient.api.Destination.class);
        CourierObservable<MessageIdResult> observable = mock(CourierObservable.class);
        when(locator.getDestination(location)).thenReturn(dest);
        when(dest.send(any(AnypointMQMessage.class))).thenReturn(observable);
        set(amq, "destinationLocator", locator);
        set(amq, "location", location);

        amq.sendToExternalDestination(BATCH);

        ArgumentCaptor<AnypointMQMessage> msg = ArgumentCaptor.forClass(AnypointMQMessage.class);
        verify(dest).send(msg.capture());
        assertEquals(BATCH, msg.getValue().getBodyAsString());
        assertNotNull(msg.getValue().getMessageId());
        assertEquals("application/json; charset=UTF-8",
                msg.getValue().getProperties().get(AnypointMQMessage.Properties.AMQ_MESSAGE_CONTENT_TYPE));
        assertEquals("UTF-8", msg.getValue().getProperties().get("MULE_ENCODING"));

        // Observer callbacks must be safe for both outcomes
        ArgumentCaptor<CourierObserver<MessageIdResult>> observer = ArgumentCaptor.forClass(CourierObserver.class);
        verify(observable).subscribe(observer.capture());
        observer.getValue().onSuccess(mock(MessageIdResult.class));
        observer.getValue().onError(new RuntimeException("401"));
        assertEquals("AMQ", amq.getSelectedDestinationType());
    }

    @Test
    public void amqFailureBeforeInitialiseIsSwallowed() {
        new AMQDestination().sendToExternalDestination(BATCH); // locator is null: must not throw
    }
}
