package org.mule.extension.jsonlogger.internal.datamask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mule.extension.jsonlogger.api.pojos.MaskingStrategy;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class JsonMaskerTest {

    private final ObjectMapper om = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return om.readTree(s);
    }

    @Test
    public void masksKeysCaseInsensitiveAndPreservesFormat() throws Exception {
        JsonMasker masker = new JsonMasker(Arrays.asList("password", " SSN "), true);
        JsonNode out = masker.mask(json("{\"user\":\"bob\",\"Password\":\"Secret12!\",\"ssn\":123456789}"));
        assertEquals("bob", out.get("user").asText());
        assertEquals("Xxxxxx**!", out.get("Password").asText());
        assertEquals("*********", out.get("ssn").asText());
    }

    @Test
    public void masksJsonPathsAndNestedStructures() throws Exception {
        JsonMasker masker = new JsonMasker(Arrays.asList("$.cards[*].number", "secret"), true);
        JsonNode out = masker.mask(json("{\"cards\":[{\"number\":\"4111\",\"type\":\"visa\"}],\"secret\":{\"a\":\"b\",\"n\":[1,2]}}"));
        assertEquals("****", out.at("/cards/0/number").asText());
        assertEquals("visa", out.at("/cards/0/type").asText());
        assertEquals("x", out.at("/secret/a").asText());
        assertEquals("*", out.at("/secret/n/1").asText());
    }

    @Test
    public void fullStrategyHidesLengthAndShape() throws Exception {
        JsonMasker masker = new JsonMasker(Collections.singletonList("token"), true, MaskingStrategy.FULL);
        JsonNode out = masker.mask(json("{\"token\":\"abc-123-very-long\",\"flag\":true}"));
        assertEquals(JsonMasker.FULL_MASK, out.get("token").asText());
        assertTrue(out.get("flag").asBoolean());
    }

    @Test
    public void hashStrategyIsDeterministic() throws Exception {
        JsonMasker masker = new JsonMasker(Collections.singletonList("email"), true, MaskingStrategy.HASH);
        String a = masker.mask(json("{\"email\":\"a@b.com\"}")).get("email").asText();
        String b = masker.mask(json("{\"email\":\"a@b.com\"}")).get("email").asText();
        String c = masker.mask(json("{\"email\":\"c@d.com\"}")).get("email").asText();
        assertTrue(a.startsWith("sha256:"));
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    public void doesNotMutateInput() throws Exception {
        JsonNode in = json("{\"password\":\"x\"}");
        new JsonMasker(Collections.singletonList("password"), true).mask(in);
        assertEquals("x", in.get("password").asText());
    }

    @Test
    public void noRulesReturnsSameInstance() throws Exception {
        JsonNode in = json("{\"a\":1}");
        JsonMasker masker = new JsonMasker(Arrays.asList("", "  "), true);
        assertFalse(masker.hasRules());
        assertSame(in, masker.mask(in));
    }
}
