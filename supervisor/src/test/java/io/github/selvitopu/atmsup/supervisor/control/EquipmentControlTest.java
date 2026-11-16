package io.github.selvitopu.atmsup.supervisor.control;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_COMMAND;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_MAINTENANCE_MODE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.RecordCommand;
import io.github.selvitopu.atmsup.supervisor.board.BoardInputSink;
import io.github.selvitopu.atmsup.supervisor.board.BoardResult;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpClient;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.snmp4j.PDU;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;
import org.slf4j.LoggerFactory;

class EquipmentControlTest {

    /** Every failed command is logged at WARN, which is right in production and only noise here. */
    private static final Logger CONTROL_LOG = (Logger) LoggerFactory.getLogger(EquipmentControl.class);
    private static Level level;

    @BeforeAll
    static void quiet() {
        level = CONTROL_LOG.getLevel();
        CONTROL_LOG.setLevel(Level.ERROR);
    }

    @AfterAll
    static void restore() {
        CONTROL_LOG.setLevel(level);
    }

    private final Inventory inv = Inventory.from(TestInventories.v01(16101, 16162));
    private final List<List<VariableBinding>> sets = new ArrayList<>();
    private final List<BoardInput> recorded = new ArrayList<>();
    private SnmpRequestException failure;

    private final SnmpClient snmp = new SnmpClient() {
        @Override
        public List<VariableBinding> get(Device d, List<OID> oids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<VariableBinding> walk(Device d, OID subtree) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void set(Device d, List<VariableBinding> bindings) {
            if (failure != null) {
                throw failure;
            }
            sets.add(bindings);
        }
    };
    private final BoardInputSink board = in -> {
        recorded.add(in);
        return CompletableFuture.completedFuture(BoardResult.ok());
    };
    private final EquipmentControl control = new EquipmentControl(inv, snmp, board);

    private static List<VariableBinding> binding(OID object, int value) {
        return List.of(new VariableBinding(scalar(object), new Integer32(value)));
    }

    @Test
    void maintenanceOnAndOffSetTheTruthValue() {
        assertThat(control.command("FDPS-B", EquipmentCommand.MAINTENANCE_ON, "supervisor1"))
                .isEqualTo(CommandOutcome.DONE);
        control.command("FDPS-B", EquipmentCommand.MAINTENANCE_OFF, "supervisor1");

        assertThat(sets).containsExactly(binding(EQ_MAINTENANCE_MODE, 1), binding(EQ_MAINTENANCE_MODE, 2));
        assertThat(recorded).containsExactly(
                new RecordCommand("FDPS-B", "MAINTENANCE_ON", "supervisor1", "DONE"),
                new RecordCommand("FDPS-B", "MAINTENANCE_OFF", "supervisor1", "DONE"));
    }

    @Test
    void restartAndMakeActiveWriteTheCommandObject() {
        control.command("FDPS-B", EquipmentCommand.RESTART, "supervisor1");
        control.command("FDPS-B", EquipmentCommand.MAKE_ACTIVE, "supervisor1");

        assertThat(sets).containsExactly(binding(EQ_COMMAND, 3), binding(EQ_COMMAND, 2));
    }

    @Test
    void aRefusalIsRecordedAndReported() {
        failure = new SnmpRequestException(Kind.ERROR_STATUS, "inconsistentValue at index 1", PDU.inconsistentValue);

        assertThat(control.command("FDPS-B", EquipmentCommand.RESTART, "supervisor1"))
                .isEqualTo(CommandOutcome.REFUSED);
        assertThat(recorded).containsExactly(new RecordCommand("FDPS-B", "RESTART", "supervisor1", "REFUSED"));
    }

    @Test
    void aTimeoutIsNoAnswer() {
        failure = new SnmpRequestException(Kind.TIMEOUT, "no response from 127.0.0.1:16106");

        assertThat(control.command("FDPS-B", EquipmentCommand.MAINTENANCE_ON, "supervisor1"))
                .isEqualTo(CommandOutcome.NO_ANSWER);
        assertThat(recorded).containsExactly(
                new RecordCommand("FDPS-B", "MAINTENANCE_ON", "supervisor1", "NO_ANSWER"));
    }

    @Test
    void wrongOperatorCredentialsAreAFailureNotASilentUnit() {
        failure = new SnmpRequestException(Kind.AUTH_FAILURE, "usmStatsWrongDigests");

        assertThat(control.command("FDPS-B", EquipmentCommand.MAINTENANCE_ON, "supervisor1"))
                .isEqualTo(CommandOutcome.FAILED);
    }

    @Test
    void unknownEquipmentIsRefusedBeforeAnythingIsSent() {
        assertThatThrownBy(() -> control.command("NOPE", EquipmentCommand.RESTART, "supervisor1"))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(sets).isEmpty();
        assertThat(recorded).isEmpty();
    }
}
