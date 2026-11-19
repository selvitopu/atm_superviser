package io.github.selvitopu.atmsup.supervisor;

import io.github.selvitopu.atmsup.inventory.EngineBootsStore;
import io.github.selvitopu.atmsup.inventory.EngineIds;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.InventoryProperties;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.inventory.UserCredentials;
import io.github.selvitopu.atmsup.supervisor.alarms.AlarmEngine;
import io.github.selvitopu.atmsup.supervisor.alarms.IdGenerator;
import io.github.selvitopu.atmsup.supervisor.board.BoardDecider;
import io.github.selvitopu.atmsup.supervisor.board.BoardInputSink;
import io.github.selvitopu.atmsup.supervisor.board.BoardStore;
import io.github.selvitopu.atmsup.supervisor.board.BoardWriter;
import io.github.selvitopu.atmsup.supervisor.board.FlightDataRule;
import io.github.selvitopu.atmsup.supervisor.board.GenerationSource;
import io.github.selvitopu.atmsup.supervisor.board.ServiceEvaluator;
import io.github.selvitopu.atmsup.supervisor.board.StatusDeriver;
import io.github.selvitopu.atmsup.supervisor.board.SurveillanceRule;
import io.github.selvitopu.atmsup.supervisor.impact.ImpactAnalyzer;
import io.github.selvitopu.atmsup.supervisor.polling.DeviceBreakers;
import io.github.selvitopu.atmsup.supervisor.polling.PollDispatcher;
import io.github.selvitopu.atmsup.supervisor.polling.PollScheduler;
import io.github.selvitopu.atmsup.supervisor.polling.TrapForwarder;
import io.github.selvitopu.atmsup.supervisor.snmp.NotificationMapper;
import io.github.selvitopu.atmsup.supervisor.snmp.ObservationMapper;
import io.github.selvitopu.atmsup.supervisor.snmp.Snmp4jClient;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpClient;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpSession;
import io.github.selvitopu.atmsup.supervisor.snmp.TrapReceiver;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.support.TaskUtils;

/**
 * Wires the supervisor's shared infrastructure (inventory, clock, events, SNMP), the board and polling. Scheduling
 * drives the SSE heartbeat.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({InventoryProperties.class, SnmpProperties.class})
@EnableScheduling
public class SupervisorConfig {

    private static final Logger log = LoggerFactory.getLogger(SupervisorConfig.class);
    private static final List<String> DEMO_PASSWORDS = List.of("monitor-auth-demo", "monitor-priv-demo",
            "operator-auth-demo", "operator-priv-demo");

    @Bean
    Inventory inventory(InventoryProperties properties) {
        return Inventory.from(properties);
    }

    /** Millisecond ticks: event times are stored as DATETIME(3), so the live board holds exactly what is stored. */
    @Bean
    InstantSource instantSource() {
        return Clock.tick(Clock.systemUTC(), Duration.ofMillis(1));
    }

    @Bean
    RandomGenerator randomGenerator() {
        // Shared across scheduler threads. Neither SplittableRandom nor RandomGenerator.getDefault() (a new
        // non-thread-safe LXM instance) is safe to share, so every call goes to the calling thread's ThreadLocalRandom.
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return ThreadLocalRandom.current().nextLong();
            }

            @Override
            public double nextDouble() {
                return ThreadLocalRandom.current().nextDouble();
            }
        };
    }

    /**
     * Events stay synchronous, but a listener that throws is logged and the others still get the event: one failing
     * listener on the board writer or SNMP listener thread must not cut off the rest.
     */
    @Bean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME)
    SimpleApplicationEventMulticaster applicationEventMulticaster(BeanFactory beanFactory) {
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster(beanFactory);
        multicaster.setErrorHandler(TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER);
        return multicaster;
    }

    @Bean
    EngineBootsStore engineBootsStore(@Value("${atm.supervisor.data-dir:./data}") String dataDir) {
        return new EngineBootsStore(Path.of(dataDir, "engine-boots.json"));
    }

    /** The engine is built here and started and closed by {@link #snmpSessionLifecycle(SnmpSession, TrapReceiver)}. */
    @Bean
    SnmpSession snmpSession(Inventory inventory, SnmpProperties creds, EngineBootsStore boots,
                            @Value("${atm.supervisor.bind-host:127.0.0.1}") String bindHost,
                            @Value("${atm.supervisor.trap-port:10162}") int trapPort,
                            @Value("${atm.demo-mode:false}") boolean demoMode) {
        checkDemoCredentials(creds, demoMode);
        return new SnmpSession(inventory, creds, boots.nextBoots(EngineIds.SUPERVISOR), bindHost, trapPort);
    }

    @Bean
    SmartLifecycle snmpSessionLifecycle(SnmpSession session, TrapReceiver trapReceiver) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                session.snmp().addCommandResponder(trapReceiver);
                session.start();
                running = true;
            }

            @Override
            public void stop() {
                session.close();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            /** Stops right after the poll scheduler, so no poll is in flight when the session closes. */
            @Override
            public int getPhase() {
                return PollScheduler.PHASE - 1;
            }
        };
    }

    @Bean
    Snmp4jClient snmpClient(SnmpSession session) {
        return new Snmp4jClient(session);
    }

    @Bean
    NotificationMapper notificationMapper(Inventory inventory) {
        return new NotificationMapper(inventory);
    }

    /** Notifications are published as Spring events; a listener forwards them to the board writer asynchronously. */
    @Bean
    TrapReceiver trapReceiver(NotificationMapper mapper, ApplicationEventPublisher events, InstantSource clock) {
        return new TrapReceiver(mapper, events::publishEvent, clock);
    }

    @Bean
    ObservationMapper observationMapper() {
        return new ObservationMapper();
    }

    @Bean
    AlarmEngine alarmEngine() {
        return new AlarmEngine(IdGenerator.uuid());
    }

    @Bean
    StatusDeriver statusDeriver() {
        return new StatusDeriver();
    }

    @Bean
    ServiceEvaluator serviceEvaluator(Inventory inventory) {
        return new ServiceEvaluator(inventory, List.of(new SurveillanceRule(), new FlightDataRule()));
    }

    @Bean
    ImpactAnalyzer impactAnalyzer(Inventory inventory, ServiceEvaluator evaluator) {
        return new ImpactAnalyzer(inventory, evaluator);
    }

    @Bean
    BoardDecider boardDecider(Inventory inventory, StatusDeriver deriver, ServiceEvaluator evaluator,
                              AlarmEngine alarms,
                              @Value("${atm.supervisor.telemetry-sample-interval:30s}") Duration sampleInterval) {
        return new BoardDecider(inventory, deriver, evaluator, alarms, sampleInterval);
    }

    /** Started and stopped by the context (see {@link BoardWriter#PHASE}); its store is the history module's. */
    @Bean
    BoardWriter boardWriter(Inventory inventory, BoardStore store, BoardDecider decider, InstantSource clock,
                            ApplicationEventPublisher events, MeterRegistry meters,
                            @Value("${atm.supervisor.heartbeat-interval:60s}") Duration heartbeatInterval) {
        return new BoardWriter(inventory, store, decider, clock, events, meters, heartbeatInterval);
    }

    @Bean
    DeviceBreakers deviceBreakers(MeterRegistry meters) {
        return new DeviceBreakers(meters, DeviceBreakers.defaults());
    }

    /** Probes and full polls together share these slots. */
    @Bean
    PollDispatcher pollDispatcher(@Value("${atm.supervisor.max-concurrent-polls:8}") int maxConcurrent) {
        return new PollDispatcher(maxConcurrent);
    }

    /** Started by BoardReady; stopped by the context before the SNMP session (see {@link PollScheduler#PHASE}). */
    @Bean
    PollScheduler pollScheduler(Inventory inventory, SnmpClient client, ObservationMapper mapper,
                                BoardInputSink sink, GenerationSource generations, DeviceBreakers breakers,
                                PollDispatcher dispatcher, InstantSource clock, RandomGenerator random,
                                MeterRegistry meters) {
        return new PollScheduler(inventory, client, mapper, sink, generations, breakers, dispatcher, clock, random,
                PollScheduler.Delayer.virtualThreads(), meters);
    }

    @Bean
    TrapForwarder trapForwarder(BoardInputSink sink) {
        return new TrapForwarder(sink);
    }

    /**
     * Demo passwords are refused unless {@code atm.demo-mode} is true; then one warning is logged.
     *
     * @throws IllegalStateException if a configured password is a shipped demo default and demo mode is off
     */
    static void checkDemoCredentials(SnmpProperties creds, boolean demoMode) {
        if (!isDemo(creds.monitor()) && !isDemo(creds.operator())) {
            return;
        }
        if (!demoMode) {
            throw new IllegalStateException("Demo SNMPv3 credentials are only allowed with atm.demo-mode=true");
        }
        log.warn("Using demo SNMPv3 credentials; set SNMP_* environment variables for anything beyond a local demo");
    }

    private static boolean isDemo(UserCredentials user) {
        return DEMO_PASSWORDS.contains(user.authPassword()) || DEMO_PASSWORDS.contains(user.privPassword());
    }
}
