package io.github.selvitopu.atmsup.supervisor.control;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.RecordCommand;
import io.github.selvitopu.atmsup.supervisor.board.BoardInputSink;
import io.github.selvitopu.atmsup.supervisor.board.BoardResult;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpClient;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.PDU;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Sends a user's command to a unit and records it as events in the event log: who sent what to which unit, first as
 * {@link CommandOutcome#ISSUED} before the SET and then with what came of it. A command whose intent cannot be stored
 * is never sent. The unit's new state arrives the usual way, by its trap and the poll it causes.
 */
@Service
public class EquipmentControl {

    /**
     * The longest a command waits for the board writer to store one of its records. The writer stores a record in
     * milliseconds; a longer wait means the database has stalled, and the panel must not hang on it.
     */
    static final Duration RECORD_TIMEOUT = Duration.ofSeconds(3);

    private static final Logger log = LoggerFactory.getLogger(EquipmentControl.class);

    private final Inventory inventory;
    private final SnmpClient snmp;
    private final BoardInputSink board;
    private final Duration recordTimeout;

    @Autowired
    public EquipmentControl(Inventory inventory, SnmpClient snmp, BoardInputSink board) {
        this(inventory, snmp, board, RECORD_TIMEOUT);
    }

    /** Tests wait less than {@link #RECORD_TIMEOUT} for a record that never commits. */
    EquipmentControl(Inventory inventory, SnmpClient snmp, BoardInputSink board, Duration recordTimeout) {
        this.inventory = inventory;
        this.snmp = snmp;
        this.board = board;
        this.recordTimeout = recordTimeout;
    }

    /**
     * Records the command's intent, sends it, and records its outcome. If the intent is not stored within the record
     * timeout, nothing is sent: the answer is {@code NOT_SENT}, and {@code NOT_SENT} is also recorded, so that an
     * intent that commits late is followed by it. An outcome that cannot be stored is still answered, since the unit
     * has acted or not, and is logged as an error; the intent is then the audit trail.
     *
     * @throws java.util.NoSuchElementException for unknown equipment, before anything is recorded or sent
     */
    public CommandResult command(String equipmentId, EquipmentCommand command, String actor) {
        Device device = inventory.device(equipmentId);
        BoardResult intent = record(equipmentId, command, actor, CommandOutcome.ISSUED);
        if (!intent.committed()) {
            log.error("{} by {} to {} was not sent: its intent could not be recorded ({})", command, actor,
                    equipmentId, intent.error());
            board.submit(new RecordCommand(equipmentId, command.name(), actor, CommandOutcome.NOT_SENT.name()));
            return new CommandResult(CommandOutcome.NOT_SENT, "The command was not sent: it could not be recorded");
        }
        CommandResult result = send(device, command);
        if (result.outcome() != CommandOutcome.DONE) {
            log.warn("{} by {} to {}: {} ({})", command, actor, equipmentId, result.outcome(), result.reason());
        }
        BoardResult recorded = record(equipmentId, command, actor, result.outcome());
        if (!recorded.committed()) {
            log.error("{} by {} to {} ({}) could not be recorded: {}", command, actor, equipmentId, result.outcome(),
                    recorded.error());
        }
        return result;
    }

    /** Waits at most the record timeout; the sink's future never completes exceptionally. */
    private BoardResult record(String equipmentId, EquipmentCommand command, String actor, CommandOutcome outcome) {
        return board.submit(new RecordCommand(equipmentId, command.name(), actor, outcome.name()))
                .completeOnTimeout(BoardResult.failed("not recorded within " + recordTimeout.toMillis() + " ms"),
                        recordTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .join();
    }

    private CommandResult send(Device device, EquipmentCommand command) {
        try {
            snmp.set(device, List.of(command.binding()));
            return new CommandResult(CommandOutcome.DONE, null);
        } catch (SnmpRequestException e) {
            return switch (e.kind()) {
                case ERROR_STATUS -> e.errorStatus() == PDU.inconsistentValue
                        ? new CommandResult(CommandOutcome.REFUSED, refusal(command))
                        // a read-only agent or an operator user without write access: not the unit's state
                        : failed("The equipment rejected the operator user's request ("
                                + PDU.toErrorStatusText(e.errorStatus())
                                + "): check that user's write access on the unit");
                case TIMEOUT -> new CommandResult(CommandOutcome.NO_ANSWER, "No answer from the equipment: the command"
                        + " may or may not have been applied. Check the unit before you send it again");
                case SEND_FAILED -> failed("The command was not sent: " + e.detail());
                case AUTH_FAILURE -> failed("The command could not be sent to the equipment; check the operator SNMP"
                        + " credentials");
                case MALFORMED -> failed("The equipment's answer could not be read (" + e.detail() + "); check the"
                        + " unit before you send it again");
            };
        }
    }

    /** The agent's rules for the command: inconsistentValue means the unit's state does not allow it now. */
    private static String refusal(EquipmentCommand command) {
        return "The equipment refused the command: " + switch (command) {
            case RESTART -> "restart needs maintenance mode, and no restart in progress";
            case MAKE_ACTIVE -> "make active needs a standby unit that is operational and not in maintenance";
            case MAINTENANCE_ON, MAINTENANCE_OFF -> "it does not fit the unit's current state";
        };
    }

    private static CommandResult failed(String reason) {
        return new CommandResult(CommandOutcome.FAILED, reason);
    }
}
