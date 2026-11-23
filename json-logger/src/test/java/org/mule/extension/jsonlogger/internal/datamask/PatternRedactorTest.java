package org.mule.extension.jsonlogger.internal.datamask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;
import static org.mule.extension.jsonlogger.internal.datamask.PatternRedactor.REDACTED;

public class PatternRedactorTest {

    @Test
    public void emptyConfigDisablesRedaction() {
        assertNull(PatternRedactor.fromCsv(null));
        assertNull(PatternRedactor.fromCsv(" , "));
    }

    @Test
    public void builtInPatterns() {
        PatternRedactor r = PatternRedactor.fromCsv("email, credit_card,SSN,JWT,BEARER_TOKEN,IPV4");
        assertEquals("contact " + REDACTED + " now", r.redact("contact john.doe@acme.com now"));
        assertEquals("card " + REDACTED, r.redact("card 4111 1111 1111 1111"));
        assertEquals("ssn " + REDACTED, r.redact("ssn 123-45-6789"));
        assertEquals("t=" + REDACTED, r.redact("t=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc_DEF-1"));
        assertEquals("Authorization: " + REDACTED, r.redact("Authorization: Bearer abc.def-123"));
        assertEquals("from " + REDACTED, r.redact("from 10.0.0.12"));
        assertEquals("nothing here", r.redact("nothing here"));
    }

    @Test
    public void customRegex() {
        PatternRedactor r = PatternRedactor.fromCsv("ACC-\\d{6}");
        assertEquals("account " + REDACTED, r.redact("account ACC-123456"));
    }

    @Test
    public void redactsNestedValuesAndSkipsReservedFields() throws Exception {
        ObjectNode node = (ObjectNode) new ObjectMapper().readTree(
                "{\"correlationId\":\"a@b.com\",\"message\":\"user a@b.com\",\"content\":{\"list\":[\"x@y.io\",1]}}");
        PatternRedactor.fromCsv("EMAIL").redactInPlace(node, Collections.singleton("correlationId"));
        assertEquals("a@b.com", node.get("correlationId").asText());
        assertEquals("user " + REDACTED, node.get("message").asText());
        assertEquals(REDACTED, node.at("/content/list/0").asText());
        assertEquals(1, node.at("/content/list/1").asInt());
    }

    @Test(expected = java.util.regex.PatternSyntaxException.class)
    public void invalidRegexFailsFast() {
        PatternRedactor.fromCsv("([unclosed");
    }
}
