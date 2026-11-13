package io.github.selvitopu.atmsup.supervisor;

import io.github.selvitopu.atmsup.inventory.EngineBootsStore;
import io.github.selvitopu.atmsup.inventory.EngineIds;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.InventoryProperties;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.inventory.UserCredentials;
import io.github.selvitopu.atmsup.supervisor.snmp.ObservationMapper;
import io.github.selvitopu.atmsup.supervisor.snmp.Snmp4jClient;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpSession;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the supervisor's shared infrastructure: inventory, clock, SNMP session, client and mapper. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({InventoryProperties.class, SnmpProperties.class})
public class SupervisorConfig {

    private static final Logger log = LoggerFactory.getLogger(SupervisorConfig.class);
    private static final List<String> DEMO_PASSWORDS = List.of("monitor-auth-demo", "monitor-priv-demo",
            "operator-auth-demo", "operator-priv-demo");

    @Bean
    Inventory inventory(InventoryProperties properties) {
        return Inventory.from(properties);
    }

    @Bean
    InstantSource instantSource() {
        return InstantSource.system();
    }

    @Bean
    RandomGenerator randomGenerator() {
        // shared across scheduler threads: SplittableRandom is not thread-safe, the default generator is
        return RandomGenerator.getDefault();
    }

    @Bean
    EngineBootsStore engineBootsStore(@Value("${atm.supervisor.data-dir:./data}") String dataDir) {
        return new EngineBootsStore(Path.of(dataDir, "engine-boots.json"));
    }

    /** The engine is built here and started and closed by {@link #snmpSessionLifecycle(SnmpSession)}. */
    @Bean
    SnmpSession snmpSession(Inventory inventory, SnmpProperties creds, EngineBootsStore boots,
                            @Value("${atm.supervisor.bind-host:127.0.0.1}") String bindHost,
                            @Value("${atm.supervisor.trap-port:10162}") int trapPort,
                            @Value("${atm.demo-mode:false}") boolean demoMode) {
        checkDemoCredentials(creds, demoMode);
        return new SnmpSession(inventory, creds, boots.nextBoots(EngineIds.SUPERVISOR), bindHost, trapPort);
    }

    @Bean
    SmartLifecycle snmpSessionLifecycle(SnmpSession session) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
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
        };
    }

    @Bean
    Snmp4jClient snmpClient(SnmpSession session) {
        return new Snmp4jClient(session);
    }

    @Bean
    ObservationMapper observationMapper() {
        return new ObservationMapper();
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
