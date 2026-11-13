package io.github.selvitopu.atmsup.simulator.agent;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_COMMAND;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_MAINTENANCE_MODE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_STATUS;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Group;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.inventory.UserCredentials;
import io.github.selvitopu.atmsup.simulator.fixture.TestManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.PDU;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

class DeviceAgentSetIT {

    private static final String HOST = "127.0.0.1";
    private static final UserCredentials MONITOR = new UserCredentials("monitor-auth-demo", "monitor-priv-demo");
    private static final UserCredentials OPERATOR = new UserCredentials("operator-auth-demo", "operator-priv-demo");
    private static final Device DEVICE = new Device("RAD-N", DeviceType.RADAR, "Radar North", Group.SURVEILLANCE,
            HOST, 0, null, Duration.ofSeconds(5));

    /** Rejects value 3 on the command object; records what is applied. */
    private static final class RecordingHandler implements SetHandler {
        private final List<VariableBinding> applied = new CopyOnWriteArrayList<>();

        @Override
        public SetCheck check(OID oid, Variable value) {
            if (oid.equals(scalar(EQ_COMMAND)) && value.equals(new Integer32(3))) {
                return new SetCheck(PDU.inconsistentValue, "restart requires maintenance mode");
            }
            return SetCheck.OK;
        }

        @Override
        public void apply(OID oid, Variable value) {
            applied.add(new VariableBinding(oid, value));
        }

        List<VariableBinding> applied() {
            return new ArrayList<>(applied);
        }
    }

    private final RecordingHandler handler = new RecordingHandler();
    private DeviceAgent agent;
    private TestManager monitorMgr;
    private TestManager operatorMgr;

    private static VariableBinding vb(OID oid, int value) {
        return new VariableBinding(oid, new Integer32(value));
    }

    private static VariableBinding maint(int value) {
        return vb(scalar(EQ_MAINTENANCE_MODE), value);
    }

    private static VariableBinding cmd(int value) {
        return vb(scalar(EQ_COMMAND), value);
    }

    @BeforeEach
    void startAgent() {
        agent = new DeviceAgent(DEVICE, new SnmpProperties(MONITOR, OPERATOR), 1, HOST, 0,
                () -> MibView.builder().build(), handler);
        agent.start();
        monitorMgr = new TestManager(HOST, agent.boundPort(), "monitor", MONITOR.authPassword(),
                MONITOR.privPassword());
        operatorMgr = new TestManager(HOST, agent.boundPort(), "operator", OPERATOR.authPassword(),
                OPERATOR.privPassword());
    }

    @AfterEach
    void stopAgent() {
        monitorMgr.close();
        operatorMgr.close();
        agent.close();
    }

    @Test
    void monitorCannotSet() {
        PDU r = monitorMgr.set(maint(1));

        assertThat(r.getErrorStatus()).isEqualTo(PDU.noAccess);
        assertThat(r.getErrorIndex()).isEqualTo(1);
        assertThat(handler.applied()).isEmpty();
    }

    @Test
    void unknownOidNotWritable() {
        assertThat(operatorMgr.set(vb(scalar(EQ_STATUS), 1)).getErrorStatus()).isEqualTo(PDU.notWritable);
    }

    @Test
    void commandNoneIsNotSettable() {
        assertThat(operatorMgr.set(cmd(1)).getErrorStatus()).isEqualTo(PDU.wrongValue);
    }

    @Test
    void wrongTypeRejected() {
        PDU r = operatorMgr.set(new VariableBinding(scalar(EQ_MAINTENANCE_MODE), new OctetString("x")));

        assertThat(r.getErrorStatus()).isEqualTo(PDU.wrongType);
    }

    @Test
    void outOfRangeRejected() {
        assertThat(operatorMgr.set(maint(7)).getErrorStatus()).isEqualTo(PDU.wrongValue);
    }

    @Test
    void modelRejectionIsInconsistentValueAndNothingApplied() {
        PDU r = operatorMgr.set(maint(1), cmd(3));

        assertThat(r.getErrorStatus()).isEqualTo(PDU.inconsistentValue);
        assertThat(r.getErrorIndex()).isEqualTo(2);
        assertThat(handler.applied()).isEmpty();
    }

    @Test
    void successAppliesInOrderAndEchoes() {
        PDU r = operatorMgr.set(maint(1), cmd(2));

        assertThat(r.getErrorStatus()).isZero();
        assertThat(r.toArray()).containsExactly(maint(1), cmd(2));
        assertThat(handler.applied()).containsExactly(maint(1), cmd(2));
    }
}
