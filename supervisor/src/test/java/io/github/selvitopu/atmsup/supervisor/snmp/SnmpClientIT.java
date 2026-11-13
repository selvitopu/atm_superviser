package io.github.selvitopu.atmsup.supervisor.snmp;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_STATUS;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.SDPS_SENSOR_ENTRY;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.inventory.UserCredentials;
import io.github.selvitopu.atmsup.simulator.fixture.FreePorts;
import io.github.selvitopu.atmsup.simulator.fixture.SimulatorFixture;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.simulator.model.Fault;
import io.github.selvitopu.atmsup.simulator.model.FaultRequest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;

class SnmpClientIT {

    @TempDir
    Path dataDir;

    private Inventory inv;
    private SimulatorFixture fixture;
    private SnmpSession session;
    private Snmp4jClient client;

    @BeforeEach
    void start() {
        inv = Inventory.from(TestInventories.v01(FreePorts.udpBlock(7), FreePorts.udp(1)[0]));
        fixture = SimulatorFixture.start(inv, TestInventories.demoCredentials(), dataDir);
        session = newSession(TestInventories.demoCredentials());
        client = new Snmp4jClient(session);
    }

    @AfterEach
    void stop() {
        session.close();
        fixture.close();
    }

    private SnmpSession newSession(SnmpProperties creds) {
        SnmpSession s = new SnmpSession(inv, creds, 1, TestInventories.HOST, 0);
        s.start();
        return s;
    }

    @Test
    void getsRadarScalars() throws Exception {
        List<VariableBinding> vbs = client.get(inv.device("RAD-N"), PollPlan.forType(DeviceType.RADAR).scalars());

        assertThat(vbs).hasSize(14);
        assertThat(vbs).noneMatch(vb -> vb.getVariable().isException());
        Observation o = new ObservationMapper().map(inv.device("RAD-N"), vbs, Map.of(), Instant.now());
        assertThat(o.equipmentId()).isEqualTo("RAD-N");
    }

    @Test
    void getsProbeObject() throws Exception {
        List<VariableBinding> vbs = client.get(inv.device("RAD-N"), List.of(PollPlan.PROBE));

        assertThat(vbs).hasSize(1);
        assertThat(vbs.get(0).getOid()).isEqualTo(SnmpConstants.sysUpTime);
        assertThat(vbs.get(0).getVariable()).isInstanceOf(TimeTicks.class);
    }

    @Test
    void walksSensorTable() throws Exception {
        assertThat(client.walk(inv.device("SDPS-A"), SDPS_SENSOR_ENTRY)).hasSize(6);
    }

    @Test
    void walkOfATableTheDeviceDoesNotHaveIsEmpty() throws Exception {
        assertThat(client.walk(inv.device("RAD-N"), SDPS_SENSOR_ENTRY)).isEmpty();
    }

    @Test
    void wrongPasswordIsAuthFailure() {
        SnmpProperties wrong = new SnmpProperties(new UserCredentials("wrong-auth-password", "monitor-priv-demo"),
                TestInventories.demoCredentials().operator());
        try (SnmpSession bad = newSession(wrong)) {
            assertThatThrownBy(() -> new Snmp4jClient(bad).get(inv.device("RAD-N"), List.of(scalar(EQ_STATUS))))
                    .isInstanceOfSatisfying(SnmpRequestException.class, e -> {
                        assertThat(e.kind()).isEqualTo(SnmpRequestException.Kind.AUTH_FAILURE);
                        assertThat(e.getMessage()).isEqualTo("AUTH_FAILURE: usmStatsWrongDigests");
                    });
        }
    }

    @Test
    void unreachableIsTimeout() {
        fixture.centre().applyFault("RAD-N", new FaultRequest(Fault.UNREACHABLE, null));

        assertThatThrownBy(() -> client.get(inv.device("RAD-N"), List.of(scalar(EQ_STATUS))))
                .isInstanceOf(SnmpRequestException.class)
                .hasMessage("TIMEOUT: no response from 127.0.0.1:" + inv.device("RAD-N").port());
    }

    @Test
    void targetUsesMonitorAuthPrivAndTheConfiguredTimeouts() {
        var target = session.target(inv.device("RAD-N"));

        assertThat(target.getSecurityName().toString()).isEqualTo("monitor");
        assertThat(target.getSecurityLevel()).isEqualTo(org.snmp4j.security.SecurityLevel.AUTH_PRIV);
        assertThat(target.getTimeout()).isEqualTo(1500);
        assertThat(target.getRetries()).isEqualTo(1);
        assertThat(target.getVersion()).isEqualTo(SnmpConstants.version3);
    }
}
