package io.github.selvitopu.atmsup.simulator.fixture;

import io.github.selvitopu.atmsup.inventory.EngineBootsStore;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.simulator.agent.AgentHost;
import io.github.selvitopu.atmsup.simulator.centre.Centre;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.SplittableRandom;

/**
 * A running simulator without Spring: a {@link Centre} and an {@link AgentHost} bound to 127.0.0.1, ticking once a
 * second. Used by this module's tests and, through the test-jar, by the supervisor's integration tests.
 */
public final class SimulatorFixture implements AutoCloseable {

    private final Centre centre;
    private final AgentHost host;

    private SimulatorFixture(Centre centre, AgentHost host) {
        this.centre = centre;
        this.host = host;
    }

    /** Starts every agent of {@code inv}; engine boots are kept in {@code dataDir/engine-boots.json}. */
    public static SimulatorFixture start(Inventory inv, SnmpProperties creds, Path dataDir) {
        InstantSource clock = InstantSource.system();
        Centre centre = new Centre(inv, clock, new SplittableRandom());
        AgentHost host = new AgentHost(inv, creds, centre, new EngineBootsStore(dataDir.resolve("engine-boots.json")),
                TestInventories.HOST, clock);
        host.start();
        return new SimulatorFixture(centre, host);
    }

    public Centre centre() {
        return centre;
    }

    public AgentHost host() {
        return host;
    }

    @Override
    public void close() {
        host.close();
    }
}
