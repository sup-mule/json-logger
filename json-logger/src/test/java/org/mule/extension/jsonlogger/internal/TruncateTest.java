package org.mule.extension.jsonlogger.internal;

import org.junit.Test;

import static org.junit.Assert.*;

public class TruncateTest {

    @Test
    public void truncatesOnlyWhenOverLimit() {
        assertEquals("abc", JsonloggerOperations.truncate("abc", 0));
        assertEquals("abc", JsonloggerOperations.truncate("abc", 3));
        assertEquals("ab" + JsonloggerOperations.TRUNCATED_SUFFIX, JsonloggerOperations.truncate("abc", 2));
        assertNull(JsonloggerOperations.truncate(null, 2));
    }

    @Test
    public void csvParsing() {
        assertTrue(JsonloggerConfiguration.csvToList(null).isEmpty());
        assertEquals(java.util.Arrays.asList("a", "b"), JsonloggerConfiguration.csvToList(" a, ,b "));
    }
}
