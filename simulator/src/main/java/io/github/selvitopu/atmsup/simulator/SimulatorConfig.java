package io.github.selvitopu.atmsup.simulator;

import io.github.selvitopu.atmsup.inventory.EngineBootsStore;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.InventoryProperties;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.inventory.UserCredentials;
import io.github.selvitopu.atmsup.simulator.agent.AgentHost;
import io.github.selvitopu.atmsup.simulator.centre.Centre;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the simulator: inventory, centre, engine boots store and the agent host with its lifecycle. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({InventoryProperties.class, SnmpProperties.class})
public class SimulatorConfig {

    private static final Logger log = LoggerFactory.getLogger(SimulatorConfig.class);
    private static final List<String> DEMO_PASSWORDS = List.of("monitor-auth-demo", "monitor-priv-demo",
            "operator-auth-demo", "operator-priv-demo");

    @Bean
    InstantSource instantSource() {
        return InstantSource.system();
    }

    @Bean
    RandomGenerator randomGenerator() {
        return new SplittableRandom();
    }

    @Bean
    Inventory inventory(InventoryProperties properties) {
        return Inventory.from(properties);
    }

    @Bean
    Centre centre(Inventory inventory, InstantSource clock, RandomGenerator rnd,
                  @Value("${atm.simulator.restart-duration:8s}") Duration restartDuration) {
        return new Centre(inventory, clock, rnd, restartDuration);
    }

    @Bean
    EngineBootsStore engineBootsStore(@Value("${atm.simulator.data-dir:./data}") String dataDir) {
        return new EngineBootsStore(Path.of(dataDir, "engine-boots.json"));
    }

    @Bean
    AgentHost agentHost(Inventory inventory, SnmpProperties creds, Centre centre, EngineBootsStore boots,
                        InstantSource clock, @Value("${atm.simulator.bind-host:127.0.0.1}") String bindHost,
                        @Value("${atm.demo-mode:false}") boolean demoMode) {
        checkDemoCredentials(creds, demoMode);
        return new AgentHost(inventory, creds, centre, boots, bindHost, clock);
    }

    /** Starts the agents once the context is ready and stops them first on shutdown. */
    @Bean
    SmartLifecycle agentHostLifecycle(AgentHost host) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                host.start();
                running = true;
            }

            @Override
            public void stop() {
                host.close();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
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
