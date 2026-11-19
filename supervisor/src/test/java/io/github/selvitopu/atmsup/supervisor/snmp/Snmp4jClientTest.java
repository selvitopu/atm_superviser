package io.github.selvitopu.atmsup.supervisor.snmp;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_MAINTENANCE_MODE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.InventoryProperties;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException.Kind;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.VariableBinding;

/** Requests that never leave the supervisor; no agent is needed. */
class Snmp4jClientTest {

    /** The {@code .invalid} top-level domain never resolves (RFC 6761). */
    private static final String NO_SUCH_HOST = "no-such-unit.invalid";

    private Inventory inv;
    private SnmpSession session;
    private Snmp4jClient client;

    @BeforeEach
    void start() {
        InventoryProperties demo = TestInventories.v01(16101, 10162);
        inv = Inventory.from(new InventoryProperties(demo.centre(), NO_SUCH_HOST, demo.defaultPollInterval(),
                demo.trapTarget(), demo.devices()));
        session = new SnmpSession(inv, TestInventories.demoCredentials(), 1, TestInventories.HOST, 0);
        session.start();
        client = new Snmp4jClient(session);
    }

    @AfterEach
    void stop() {
        session.close();
    }

    @Test
    void aSetToAHostThatDoesNotResolveWasNeverSent() {
        // not a timeout: nothing reached the unit, so the outcome of the command is certain
        assertThatThrownBy(() -> client.set(inv.device("FDPS-B"),
                List.of(new VariableBinding(scalar(EQ_MAINTENANCE_MODE), new Integer32(1)))))
                .isInstanceOfSatisfying(SnmpRequestException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.SEND_FAILED))
                .hasMessage("SEND_FAILED: cannot resolve host " + NO_SUCH_HOST);
    }
}
