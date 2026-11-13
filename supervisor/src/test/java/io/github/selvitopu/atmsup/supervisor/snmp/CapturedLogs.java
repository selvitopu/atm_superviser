package io.github.selvitopu.atmsup.supervisor.snmp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Captures a class's log events and keeps them off the console (the logger stops propagating while captured). */
final class CapturedLogs implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    CapturedLogs(Class<?> source) {
        logger = (Logger) LoggerFactory.getLogger(source);
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
    }

    List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    List<String> warnings() {
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
