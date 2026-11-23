package org.mule.extension.jsonlogger.internal.util;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TimestampFormatterTest {

    private static final long EPOCH = 1767262530123L; // 2026-01-01T10:15:30.123Z

    @After
    public void clear() {
        System.clearProperty("json.logger.timezone");
        System.clearProperty("json.logger.dateformat");
    }

    @Test
    public void defaultsToIsoUtcWithMillis() {
        assertEquals("2026-01-01T10:15:30.123Z", TimestampFormatter.format(EPOCH));
    }

    @Test
    public void honoursTimezoneAndPattern() {
        System.setProperty("json.logger.timezone", "America/New_York");
        assertEquals("2026-01-01T05:15:30.123-05:00", TimestampFormatter.format(EPOCH));
        System.setProperty("json.logger.dateformat", "yyyy-MM-dd HH:mm");
        assertEquals("2026-01-01 05:15", TimestampFormatter.format(EPOCH));
    }

    @Test
    public void invalidSettingsFallBack() {
        System.setProperty("json.logger.timezone", "Not/AZone");
        System.setProperty("json.logger.dateformat", "bbbb-qqq{");
        assertEquals("2026-01-01T10:15:30.123Z", TimestampFormatter.format(EPOCH));
    }
}
