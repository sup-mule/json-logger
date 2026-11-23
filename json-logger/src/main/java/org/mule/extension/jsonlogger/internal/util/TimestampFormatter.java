package org.mule.extension.jsonlogger.internal.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Formats log timestamps (java.time based, thread-safe).
 * <ul>
 *   <li>json.logger.timezone: defaults to UTC. See https://en.wikipedia.org/wiki/List_of_tz_database_time_zones</li>
 *   <li>json.logger.dateformat: optional pattern. Defaults to ISO-8601 with millis, e.g. 2026-01-01T10:15:30.123Z</li>
 * </ul>
 */
public final class TimestampFormatter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimestampFormatter.class);
    private static final String DEFAULT_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";

    private static volatile String cachedKey;
    private static volatile DateTimeFormatter cachedFormatter;

    private TimestampFormatter() {
    }

    public static String format(long epochMillis) {
        return formatter().format(Instant.ofEpochMilli(epochMillis));
    }

    private static DateTimeFormatter formatter() {
        String tz = System.getProperty("json.logger.timezone", "UTC");
        String pattern = System.getProperty("json.logger.dateformat");
        if (pattern == null || pattern.trim().isEmpty()) {
            pattern = DEFAULT_PATTERN;
        }
        String key = tz + "|" + pattern;
        DateTimeFormatter f = cachedFormatter;
        if (f == null || !key.equals(cachedKey)) {
            f = build(tz, pattern);
            cachedFormatter = f;
            cachedKey = key;
        }
        return f;
    }

    static DateTimeFormatter build(String tz, String pattern) {
        ZoneId zone;
        try {
            zone = ZoneId.of(tz);
        } catch (Exception e) {
            LOGGER.warn("Invalid json.logger.timezone '{}', defaulting to UTC", tz);
            zone = ZoneId.of("UTC");
        }
        try {
            return DateTimeFormatter.ofPattern(pattern).withZone(zone);
        } catch (Exception e) {
            LOGGER.warn("Invalid json.logger.dateformat '{}', defaulting to ISO-8601", pattern);
            return DateTimeFormatter.ofPattern(DEFAULT_PATTERN).withZone(zone);
        }
    }
}
