package io.github.selvitopu.atmsup.supervisor.control;

import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_COMMAND;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.EQ_MAINTENANCE_MODE;
import static io.github.selvitopu.atmsup.mib.AtmTsOids.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.simulator.fixture.TestInventories;
import io.github.selvitopu.atmsup.supervisor.CapturedLogs;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.RecordCommand;
import io.github.selvitopu.atmsup.supervisor.board.BoardInputSink;
import io.github.selvitopu.atmsup.supervisor.board.BoardResult;
import io.github.selvitopu.atmsup.supervisor.board.BoardWriter;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpClient;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException.Kind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.slf4j.LoggerFactory;
import org.snmp4j.PDU;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;

/** A wait on the writer that is not bounded hangs, and join() ignores interrupts: each test runs in its own thread. */
@Timeout(value = 5, threadMode = ThreadMode.SEPARATE_THREAD)
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

    /** The record wait in these tests; production waits {@link EquipmentControl#RECORD_TIMEOUT}. */
    private static final Duration WAIT = Duration.ofMillis(100);
    private static final String NOT_RECORDED = "The command was not sent: it could not be recorded";

    private final Inventory inv = Inventory.from(TestInventories.v01(16101, 16162));
    /** What reached the unit and the writer, in order: "SET", or the outcome a record carried. */
    private final List<String> steps = new ArrayList<>();
    private final List<List<VariableBinding>> sets = new ArrayList<>();
    private final List<RecordCommand> recorded = new ArrayList<>();
    /** The writer's answer to a record, by its outcome; any other record commits. */
    private final Map<String, CompletableFuture<BoardResult>> answers = new HashMap<>();
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
            steps.add("SET");
            if (failure != null) {
                throw failure;
            }
            sets.add(bindings);
        }
    };
    private final BoardInputSink board = in -> {
        RecordCommand record = (RecordCommand) in;
        recorded.add(record);
        steps.add(record.outcome());
        return answers.getOrDefault(record.outcome(), CompletableFuture.completedFuture(BoardResult.ok()));
    };
    private final EquipmentControl control = new EquipmentControl(inv, snmp, board, WAIT);

    private static List<VariableBinding> binding(OID object, int value) {
        return List.of(new VariableBinding(scalar(object), new Integer32(value)));
    }

    private static RecordCommand record(String command, String outcome) {
        return new RecordCommand("FDPS-B", command, "supervisor1", outcome, null);
    }

    /** The records as submitted, with their command ids blanked, to compare what was recorded apart from the id. */
    private List<RecordCommand> unidentified() {
        return recorded.stream()
                .map(r -> new RecordCommand(r.equipmentId(), r.command(), r.actor(), r.outcome(), null)).toList();
    }

    private CommandResult refusedWith(int errorStatus, EquipmentCommand command) {
        failure = new SnmpRequestException(Kind.ERROR_STATUS, PDU.toErrorStatusText(errorStatus) + " at index 1",
                errorStatus);
        return control.command("FDPS-B", command, "supervisor1");
    }

    private static List<String> errors(CapturedLogs logs) {
        return logs.events().stream().filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    // --- what is sent ------------------------------------------------------------------------------------------

    @Test
    void maintenanceOnAndOffSetTheTruthValue() {
        assertThat(control.command("FDPS-B", EquipmentCommand.MAINTENANCE_ON, "supervisor1"))
                .isEqualTo(new CommandResult(CommandOutcome.DONE, null));
        control.command("FDPS-B", EquipmentCommand.MAINTENANCE_OFF, "supervisor1");

        assertThat(sets).containsExactly(binding(EQ_MAINTENANCE_MODE, 1), binding(EQ_MAINTENANCE_MODE, 2));
        assertThat(unidentified()).containsExactly(
                record("MAINTENANCE_ON", "ISSUED"), record("MAINTENANCE_ON", "DONE"),
                record("MAINTENANCE_OFF", "ISSUED"), record("MAINTENANCE_OFF", "DONE"));
    }

    @Test
    void restartAndMakeActiveWriteTheCommandObject() {
        control.command("FDPS-B", EquipmentCommand.RESTART, "supervisor1");
        control.command("FDPS-B", EquipmentCommand.MAKE_ACTIVE, "supervisor1");

        assertThat(sets).containsExactly(binding(EQ_COMMAND, 3), binding(EQ_COMMAND, 2));
    }

    @Test
    void theIntentIsRecordedBeforeTheSetAndTheOutcomeAfterIt() {
        control.command("FDPS-B", EquipmentCommand.MAINTENANCE_ON, "supervisor1");

        assertThat(steps).containsExactly("ISSUED", "SET", "DONE");
    }

    @Test
    void unknownEquipmentIsRefusedBeforeAnythingIsSent() {
        assertThatThrownBy(() -> control.command("NOPE", EquipmentCommand.RESTART, "supervisor1"))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(steps).isEmpty();
    }

    // --- what the unit answered --------------------------------------------------------------------------------

    @Test
    void inconsistentValueIsARefusalThatGivesTheRuleOfTheCommand() {
        assertThat(refusedWith(PDU.inconsistentValue, EquipmentCommand.RESTART)).isEqualTo(new CommandResult(
                CommandOutcome.REFUSED, "The equipment refused the command: restart needs maintenance mode, and no"
                + " restart in progress"));
        assertThat(refusedWith(PDU.inconsistentValue, EquipmentCommand.MAKE_ACTIVE)).isEqualTo(new CommandResult(
                CommandOutcome.REFUSED, "The equipment refused the command: make active needs a standby unit that is"
                + " operational and not in maintenance"));
        assertThat(refusedWith(PDU.inconsistentValue, EquipmentCommand.MAINTENANCE_ON)).isEqualTo(new CommandResult(
                CommandOutcome.REFUSED, "The equipment refused the command: it does not fit the unit's current state"));
        assertThat(refusedWith(PDU.inconsistentValue, EquipmentCommand.MAINTENANCE_OFF).reason())
                .isEqualTo("The equipment refused the command: it does not fit the unit's current state");
        assertThat(unidentified()).contains(record("RESTART", "REFUSED"));
    }

    @Test
    void anyOtherErrorStatusIsAFailureOfTheOperatorUsersAccessNotARefusal() {
        // a read-only agent or an operator user without write access: the unit's state is not the cause
        assertThat(refusedWith(PDU.noAccess, EquipmentCommand.RESTART)).isEqualTo(new CommandResult(
                CommandOutcome.FAILED, "The equipment rejected the operator user's request (No access): check that"
                + " user's write access on the unit"));
        assertThat(refusedWith(PDU.notWritable, EquipmentCommand.MAINTENANCE_ON).reason())
                .isEqualTo("The equipment rejected the operator user's request (Not writable): check that user's"
                        + " write access on the unit");
        assertThat(refusedWith(PDU.wrongValue, EquipmentCommand.MAKE_ACTIVE).outcome())
                .isEqualTo(CommandOutcome.FAILED);
        assertThat(refusedWith(PDU.authorizationError, EquipmentCommand.MAINTENANCE_OFF).outcome())
                .isEqualTo(CommandOutcome.FAILED);
        assertThat(unidentified()).contains(record("RESTART", "FAILED")).doesNotContain(record("RESTART", "REFUSED"));
    }

    @Test
    void aTimeoutIsNoAnswer() {
        failure = new SnmpRequestException(Kind.TIMEOUT, "no response from 127.0.0.1:16106");

        assertThat(control.command("FDPS-B", EquipmentCommand.MAINTENANCE_ON, "supervisor1"))
                .isEqualTo(new CommandResult(CommandOutcome.NO_ANSWER, "No answer from the equipment: the command may"
                        + " or may not have been applied. Check the unit before you send it again"));
        assertThat(unidentified()).containsExactly(record("MAINTENANCE_ON", "ISSUED"), record("MAINTENANCE_ON", "NO_ANSWER"));
    }

    @Test
    void wrongOperatorCredentialsAreAFailureNotASilentUnit() {
        failure = new SnmpRequestException(Kind.AUTH_FAILURE, "usmStatsWrongDigests");

        assertThat(control.command("FDPS-B", EquipmentCommand.MAINTENANCE_ON, "supervisor1"))
                .isEqualTo(new CommandResult(CommandOutcome.FAILED, "The command could not be sent to the equipment;"
                        + " check the operator SNMP credentials"));
    }

    @Test
    void aSetThatNeverLeftIsAFailureNotAnUncertainAnswer() {
        failure = new SnmpRequestException(Kind.SEND_FAILED, "cannot resolve host fdps-b.example");

        assertThat(control.command("FDPS-B", EquipmentCommand.MAINTENANCE_ON, "supervisor1"))
                .isEqualTo(new CommandResult(CommandOutcome.FAILED,
                        "The command was not sent: cannot resolve host fdps-b.example"));
    }

    @Test
    void theIntentAndItsOutcomeShareOneCommandId() {
        control.command("FDPS-B", EquipmentCommand.MAINTENANCE_ON, "supervisor1");
        control.command("FDPS-B", EquipmentCommand.MAINTENANCE_OFF, "supervisor1");

        assertThat(recorded).hasSize(4);
        assertThat(recorded.get(0).commandId()).isNotBlank().isEqualTo(recorded.get(1).commandId());
        assertThat(recorded.get(2).commandId()).isNotBlank().isEqualTo(recorded.get(3).commandId());
        assertThat(recorded.get(2).commandId()).isNotEqualTo(recorded.get(0).commandId());
    }

    @Test
    void aNotSentCommandCarriesTheIdOfItsIntent() {
        answers.put("ISSUED", CompletableFuture.completedFuture(BoardResult.failed("connection refused")));

        try (CapturedLogs logs = new CapturedLogs(EquipmentControl.class)) {
            control.command("FDPS-B", EquipmentCommand.MAKE_ACTIVE, "supervisor1");
        }

        assertThat(recorded).extracting(RecordCommand::outcome).containsExactly("ISSUED", "NOT_SENT");
        assertThat(recorded.get(0).commandId()).isNotBlank().isEqualTo(recorded.get(1).commandId());
    }

    // --- fail closed: an intent that is not recorded is never sent ---------------------------------------------

    @Test
    void anIntentTheStoreCannotCommitIsNeverSent() {
        answers.put("ISSUED", CompletableFuture.completedFuture(BoardResult.failed("connection refused")));

        try (CapturedLogs logs = new CapturedLogs(EquipmentControl.class)) {
            assertThat(control.command("FDPS-B", EquipmentCommand.MAKE_ACTIVE, "supervisor1"))
                    .isEqualTo(new CommandResult(CommandOutcome.NOT_SENT, NOT_RECORDED));
            assertThat(errors(logs)).singleElement().asString().contains("MAKE_ACTIVE by supervisor1 to FDPS-B was"
                    + " not sent").contains("connection refused");
        }
        assertThat(sets).isEmpty();
        assertThat(steps).containsExactly("ISSUED", "NOT_SENT");
    }

    @Test
    void aStoppedWriterMeansNothingIsSent() {
        BoardWriter stopped = new BoardWriter(inv, null, null, InstantSource.system(), event -> { },
                new SimpleMeterRegistry(), Duration.ofMinutes(1), null, Duration.ofMinutes(5));
        EquipmentControl control = new EquipmentControl(inv, snmp, stopped, WAIT);

        try (CapturedLogs logs = new CapturedLogs(EquipmentControl.class)) {
            assertThat(control.command("FDPS-B", EquipmentCommand.RESTART, "supervisor1"))
                    .isEqualTo(new CommandResult(CommandOutcome.NOT_SENT, NOT_RECORDED));
            assertThat(errors(logs)).singleElement().asString().contains("The board writer is not running");
        }
        assertThat(sets).isEmpty();
    }

    @Test
    void anIntentNotRecordedInTimeIsNeverSentAndIsFollowedByNotSent() {
        answers.put("ISSUED", new CompletableFuture<>()); // a stalled database: the commit never returns

        try (CapturedLogs logs = new CapturedLogs(EquipmentControl.class)) {
            assertThat(control.command("FDPS-B", EquipmentCommand.MAINTENANCE_ON, "supervisor1"))
                    .isEqualTo(new CommandResult(CommandOutcome.NOT_SENT, NOT_RECORDED));
            assertThat(errors(logs)).singleElement().asString().contains("not recorded within 100 ms");
        }
        assertThat(sets).isEmpty();
        // should the intent commit late, the writer stores NOT_SENT after it: the log never shows an unsent command
        // as merely issued
        assertThat(unidentified()).containsExactly(record("MAINTENANCE_ON", "ISSUED"), record("MAINTENANCE_ON", "NOT_SENT"));
    }

    // --- an outcome that is not recorded is still answered -----------------------------------------------------

    @Test
    void anOutcomeTheStoreCannotCommitIsStillAnswered() {
        answers.put("DONE", CompletableFuture.completedFuture(BoardResult.failed("connection refused")));

        try (CapturedLogs logs = new CapturedLogs(EquipmentControl.class)) {
            assertThat(control.command("FDPS-B", EquipmentCommand.MAINTENANCE_ON, "supervisor1").outcome())
                    .isEqualTo(CommandOutcome.DONE); // the unit has acted: the ISSUED record is the audit trail
            assertThat(errors(logs)).singleElement().asString().contains("could not be recorded");
        }
        assertThat(sets).hasSize(1);
    }

    @Test
    void anOutcomeNotRecordedInTimeIsStillAnswered() {
        answers.put("REFUSED", new CompletableFuture<>());

        try (CapturedLogs logs = new CapturedLogs(EquipmentControl.class)) {
            assertThat(refusedWith(PDU.inconsistentValue, EquipmentCommand.RESTART).outcome())
                    .isEqualTo(CommandOutcome.REFUSED);
            assertThat(errors(logs)).singleElement().asString().contains("not recorded within 100 ms");
        }
    }
}
