package io.github.selvitopu.atmsup.simulator;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_COMMAND;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_MAINTENANCE_MODE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static java.time.Duration.ofMillis;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.simulator.fixture.FreePorts;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.simulator.fixture.TestManager;
import io.github.selvitopu.atmsup.simulator.fixture.TestTrapReceiver;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.snmp4j.PDU;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SimulatorSetIT {

    private static final int FIRST_PORT = FreePorts.udpBlock(7);
    private static final int TRAP_PORT = FreePorts.udp(1)[0];

    private static TestTrapReceiver receiver;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        TestInventories.asProperties(TestInventories.v01(FIRST_PORT, TRAP_PORT))
                .forEach((k, v) -> registry.add(k, () -> v));
        registry.add("atm.snmp.monitor.auth-password", () -> "monitor-auth-demo");
        registry.add("atm.snmp.monitor.priv-password", () -> "monitor-priv-demo");
        registry.add("atm.snmp.operator.auth-password", () -> "operator-auth-demo");
        registry.add("atm.snmp.operator.priv-password", () -> "operator-priv-demo");
        String dataDir = Files.createTempDirectory("atm-sim-set-it").toString();
        registry.add("atm.simulator.data-dir", () -> dataDir);
        registry.add("atm.demo-mode", () -> "true");
        registry.add("atm.simulator.bind-host", () -> "127.0.0.1");
        registry.add("atm.simulator.restart-duration", () -> "1s");
    }

    @BeforeAll
    static void startReceiver() {
        receiver = new TestTrapReceiver(TRAP_PORT, TestInventories.demoCredentials(),
                Inventory.from(TestInventories.v01(FIRST_PORT, TRAP_PORT)).devices());
    }

    @AfterAll
    static void stopReceiver() {
        receiver.close();
    }

    /** One request on a fresh session, because a restarted agent has a new engine boots counter. */
    private static PDU operatorGet(OID oid) {
        try (TestManager m = new TestManager("127.0.0.1", FIRST_PORT, "operator", "operator-auth-demo",
                "operator-priv-demo")) {
            return m.get(oid);
        }
    }

    private static PDU operatorSet(OID oid, int value) {
        try (TestManager m = new TestManager("127.0.0.1", FIRST_PORT, "operator", "operator-auth-demo",
                "operator-priv-demo")) {
            return m.set(new VariableBinding(oid, new Integer32(value)));
        }
    }

    @Test
    void snmpSetMaintenanceAndRestart() {
        assertThat(operatorSet(scalar(EQ_MAINTENANCE_MODE), 1).getErrorStatus()).isEqualTo(PDU.noError);
        assertThat(operatorGet(scalar(EQ_MAINTENANCE_MODE)).getVariable(scalar(EQ_MAINTENANCE_MODE)))
                .isEqualTo(new Integer32(1));

        assertThat(operatorSet(scalar(EQ_COMMAND), 3).getErrorStatus()).isEqualTo(PDU.noError);

        // the agent goes away shortly after the command and comes back as a new one
        await().atMost(ofMillis(1500)).pollInterval(ofMillis(50))
                .untilAsserted(() -> assertThat(operatorGet(SnmpConstants.sysUpTime)).isNull());
        await().atMost(ofSeconds(3)).pollInterval(ofMillis(100)).untilAsserted(() -> {
            PDU r = operatorGet(SnmpConstants.sysUpTime);
            assertThat(r).isNotNull();
            assertThat(((TimeTicks) r.getVariable(SnmpConstants.sysUpTime)).toMilliseconds()).isLessThan(3000);
        });
    }
}
