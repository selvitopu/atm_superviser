package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.supervisor.snmp.Notification;
import io.github.selvitopu.atmsup.supervisor.snmp.Observation;
import io.github.selvitopu.atmsup.supervisor.snmp.ProbeObservation;
import java.time.Instant;
import java.util.List;

/**
 * Everything the board writer processes, one at a time. Probe and poll results carry the generation that applies to
 * them (contact for a probe, status for a full poll), read when the probe or poll <em>started</em>; a result from an
 * older generation is discarded (amendment §2).
 */
public sealed interface BoardInput {

    record ProbeSucceeded(ProbeObservation probe, long generation) implements BoardInput {
    }

    record ProbeFailed(String equipmentId, Instant at, String reason, long generation) implements BoardInput {
    }

    record PollSucceeded(Observation obs, long generation) implements BoardInput {
    }

    record PollFailed(String equipmentId, Instant at, String reason, long generation) implements BoardInput {
    }

    record TrapReceived(Notification n) implements BoardInput {
    }

    record AcknowledgeAlarms(List<String> alarmIds, String comment, String actor) implements BoardInput {
    }

    /**
     * A command a user sent to a unit: its intent before the SET, or what came of it. Recorded in the event log, it
     * changes no state.
     */
    record RecordCommand(String equipmentId, String command, String actor, String outcome, String commandId)
            implements BoardInput {
    }

    /** {@code firstStart} is true when the event log is empty. */
    record Startup(boolean firstStart) implements BoardInput {
    }

    record HeartbeatTick() implements BoardInput {
    }

    record Shutdown() implements BoardInput {
    }
}
