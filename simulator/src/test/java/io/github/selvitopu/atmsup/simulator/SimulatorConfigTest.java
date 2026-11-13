package io.github.selvitopu.atmsup.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.inventory.UserCredentials;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SimulatorConfigTest {

    private static final String WARNING =
            "Using demo SNMPv3 credentials; set SNMP_* environment variables for anything beyond a local demo";

    private static List<ILoggingEvent> logged(SnmpProperties creds) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SimulatorConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        boolean additive = logger.isAdditive();
        logger.setAdditive(false); // keep the expected WARN out of the test console
        try {
            SimulatorConfig.warnOnDemoCredentials(creds);
            return List.copyOf(appender.list);
        } finally {
            logger.setAdditive(additive);
            logger.detachAppender(appender);
        }
    }

    @Test
    void demoCredentialsWarnExactlyOnce() {
        List<ILoggingEvent> events = logged(TestInventories.demoCredentials());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.get(0).getFormattedMessage()).isEqualTo(WARNING);
    }

    @Test
    void oneDemoPasswordAmongCustomOnesStillWarns() {
        SnmpProperties mixed = new SnmpProperties(new UserCredentials("custom-monitor-auth", "custom-monitor-priv"),
                new UserCredentials("custom-operator-auth", "operator-priv-demo"));

        assertThat(logged(mixed)).hasSize(1);
    }

    @Test
    void customCredentialsDoNotWarn() {
        SnmpProperties custom = new SnmpProperties(new UserCredentials("custom-monitor-auth", "custom-monitor-priv"),
                new UserCredentials("custom-operator-auth", "custom-operator-priv"));

        assertThat(logged(custom)).isEmpty();
    }
}
