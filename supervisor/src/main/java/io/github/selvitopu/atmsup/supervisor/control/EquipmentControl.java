package io.github.selvitopu.atmsup.supervisor.control;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.supervisor.board.BoardInput.RecordCommand;
import io.github.selvitopu.atmsup.supervisor.board.BoardInputSink;
import io.github.selvitopu.atmsup.supervisor.board.BoardResult;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpClient;
import io.github.selvitopu.atmsup.supervisor.snmp.SnmpRequestException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends a user's command to a unit and records it, whatever its outcome, as an event in the event log: who sent what,
 * to which unit, and what came of it. The unit's new state arrives the usual way, by its trap and the poll it causes.
 */
@Service
public class EquipmentControl {

    private static final Logger log = LoggerFactory.getLogger(EquipmentControl.class);

    private final Inventory inventory;
    private final SnmpClient snmp;
    private final BoardInputSink board;

    public EquipmentControl(Inventory inventory, SnmpClient snmp, BoardInputSink board) {
        this.inventory = inventory;
        this.snmp = snmp;
        this.board = board;
    }

    /** @throws java.util.NoSuchElementException for unknown equipment, before anything is sent */
    public CommandOutcome command(String equipmentId, EquipmentCommand command, String actor) {
        Device device = inventory.device(equipmentId);
        CommandOutcome outcome;
        try {
            snmp.set(device, List.of(command.binding()));
            outcome = CommandOutcome.DONE;
        } catch (SnmpRequestException e) {
            outcome = switch (e.kind()) {
                case ERROR_STATUS -> CommandOutcome.REFUSED;
                case TIMEOUT -> CommandOutcome.NO_ANSWER;
                case AUTH_FAILURE, MALFORMED -> CommandOutcome.FAILED;
            };
            log.warn("{} by {} to {}: {} ({})", command, actor, equipmentId, outcome, e.getMessage());
        }
        BoardResult recorded = board.submit(new RecordCommand(equipmentId, command.name(), actor, outcome.name())).join();
        if (!recorded.committed()) {
            log.error("{} by {} to {} ({}) could not be recorded: {}", command, actor, equipmentId, outcome,
                    recorded.error());
        }
        return outcome;
    }
}
