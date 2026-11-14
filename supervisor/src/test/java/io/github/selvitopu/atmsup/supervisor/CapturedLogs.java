package io.github.selvitopu.atmsup.supervisor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Captures a class's log events and keeps them off the console (the logger stops propagating while captured). */
public final class CapturedLogs implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public CapturedLogs(Class<?> source) {
        this(source.getName());
    }

    public CapturedLogs(String loggerName) {
        logger = (Logger) LoggerFactory.getLogger(loggerName);
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    public List<String> warnings() {
        return events().stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setAdditive(true);
        appender.stop();
    }
}
