package io.github.selvitopu.atmsup.supervisor.snmp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.simulator.fixture.FreePorts;
import io.github.selvitopu.atmsup.simulator.fixture.SimulatorFixture;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.simulator.model.Fault;
import io.github.selvitopu.atmsup.simulator.model.FaultRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.PDU;
import org.snmp4j.smi.Address;

/**
 * The session receives v3 traps from the inventory's engines on its transport. (Observed: SNMP4J also accepts them
 * through the non-localised {@code monitor} entry alone; the per-device entries are kept as the explicit guarantee.)
 */
class SnmpSessionTrapIT {

    @TempDir
    Path dataDir;

    @Test
    void acceptsTrapsFromInventoryDevices() {
        int trapPort = FreePorts.udp(1)[0];
        Inventory inv = Inventory.from(TestInventories.v01(FreePorts.udpBlock(7), trapPort));
        List<PDU> traps = new CopyOnWriteArrayList<>();
        try (SimulatorFixture fixture = SimulatorFixture.start(inv, TestInventories.demoCredentials(), dataDir);
             SnmpSession session = new SnmpSession(inv, TestInventories.demoCredentials(), 1, TestInventories.HOST,
                     trapPort)) {
            session.snmp().addCommandResponder(new CommandResponder() {
                @Override
                public <A extends Address> void processPdu(CommandResponderEvent<A> event) {
                    if (event.getPDU() != null && event.getPDU().getType() == PDU.TRAP) {
                        traps.add((PDU) event.getPDU().clone());
                    }
                }
            });
            session.start();
            assertThat(session.boundPort()).isEqualTo(trapPort);

            fixture.centre().applyFault("RAD-N", new FaultRequest(Fault.FAIL, null));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(traps).isNotEmpty());
        }
    }
}
