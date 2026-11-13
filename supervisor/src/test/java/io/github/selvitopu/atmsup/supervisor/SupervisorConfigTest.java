package io.github.selvitopu.atmsup.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.inventory.UserCredentials;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SupervisorConfigTest {

    private static final String WARNING =
            "Using demo SNMPv3 credentials; set SNMP_* environment variables for anything beyond a local demo";

    private static List<ILoggingEvent> logged(SnmpProperties creds, boolean demoMode) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SupervisorConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        boolean additive = logger.isAdditive();
        logger.setAdditive(false); // keep the expected WARN out of the test console
        try {
            SupervisorConfig.checkDemoCredentials(creds, demoMode);
            return List.copyOf(appender.list);
        } finally {
            logger.setAdditive(additive);
            logger.detachAppender(appender);
        }
    }

    @Test
    void sharedRandomIsSafeUnderConcurrency() throws Exception {
        java.util.random.RandomGenerator random = new SupervisorConfig().randomGenerator();
        int threads = 8;
        int perThread = 200_000;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<long[]>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    long[] out = new long[perThread];
                    for (int i = 0; i < perThread; i++) {
                        out[i] = random.nextLong();
                    }
                    return out;
                }));
            }
            java.util.Set<Long> unique = new java.util.HashSet<>();
            for (var f : futures) {
                for (long v : f.get()) {
                    unique.add(v);
                }
            }
            assertThat(threads * perThread - unique.size()).isLessThan(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void demoCredentialsWarnExactlyOnce() {
        List<ILoggingEvent> events = logged(TestInventories.demoCredentials(), true);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.get(0).getFormattedMessage()).isEqualTo(WARNING);
    }

    @Test
    void demoCredentialsWithoutDemoModeFailStartup() {
        assertThatThrownBy(() -> logged(TestInventories.demoCredentials(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Demo SNMPv3 credentials are only allowed with atm.demo-mode=true");
    }

    @Test
    void oneDemoPasswordAmongCustomOnesStillWarns() {
        SnmpProperties mixed = new SnmpProperties(new UserCredentials("custom-monitor-auth", "custom-monitor-priv"),
                new UserCredentials("custom-operator-auth", "operator-priv-demo"));

        assertThat(logged(mixed, true)).hasSize(1);
    }

    @Test
    void customCredentialsDoNotWarn() {
        SnmpProperties custom = new SnmpProperties(new UserCredentials("custom-monitor-auth", "custom-monitor-priv"),
                new UserCredentials("custom-operator-auth", "custom-operator-priv"));

        assertThat(logged(custom, false)).isEmpty();
    }
}
