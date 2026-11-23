package org.mule.extension.jsonlogger.internal;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.rules.ExternalResource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * JUnit rule that attaches a synchronous in-memory appender to a dedicated, unique log category so tests can assert
 * on the exact log lines produced by the JSON Logger.
 */
public class LogCapture extends ExternalResource {

    private final Level level;
    private final String category = "jsonlogger.test." + UUID.randomUUID().toString().replace("-", "");
    private final List<LogEvent> events = new CopyOnWriteArrayList<>();
    private AbstractAppender appender;

    public LogCapture(Level level) {
        this.level = level;
    }

    public String category() {
        return category;
    }

    public List<String> messages() {
        return events.stream().map(e -> e.getMessage().getFormattedMessage()).collect(Collectors.toList());
    }

    public List<Level> levels() {
        return events.stream().map(LogEvent::getLevel).collect(Collectors.toList());
    }

    @Override
    protected void before() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration cfg = ctx.getConfiguration();
        appender = new AbstractAppender("capture-" + category, null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        cfg.addAppender(appender);
        LoggerConfig lc = new LoggerConfig(category, level, false);
        lc.addAppender(appender, null, null);
        cfg.addLogger(category, lc);
        ctx.updateLoggers();
    }

    @Override
    protected void after() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration cfg = ctx.getConfiguration();
        cfg.removeLogger(category);
        appender.stop();
        ctx.updateLoggers();
    }
}
