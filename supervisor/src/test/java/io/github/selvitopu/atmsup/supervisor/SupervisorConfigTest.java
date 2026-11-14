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
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.annotation.Order;

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

    static class FailingListener {
        @EventListener
        @Order(1)
        void on(String event) {
            throw new IllegalStateException("listener failed");
        }
    }

    static class RecordingListener {
        final List<String> received = new CopyOnWriteArrayList<>();

        @EventListener
        @Order(2)
        void on(String event) {
            received.add(event);
        }
    }

    @Test
    void aFailingListenerDoesNotStopDeliveryToTheOthers() {
        try (CapturedLogs logs = new CapturedLogs("org.springframework.scheduling.support.TaskUtils$LoggingErrorHandler");
             AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
                    SimpleApplicationEventMulticaster.class,
                    () -> new SupervisorConfig().applicationEventMulticaster(ctx.getBeanFactory()));
            ctx.registerBean(FailingListener.class);
            ctx.registerBean(RecordingListener.class);
            ctx.refresh();

            ctx.publishEvent("ping"); // synchronous: the failing listener runs first, on this thread

            assertThat(ctx.getBean(RecordingListener.class).received).containsExactly("ping");
            assertThat(logs.events()).singleElement().satisfies(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getThrowableProxy().getMessage()).isEqualTo("listener failed");
            });
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
