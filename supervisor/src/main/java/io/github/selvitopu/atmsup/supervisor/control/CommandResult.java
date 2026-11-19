package io.github.selvitopu.atmsup.supervisor.control;

/** The answer to a command: its outcome and, for any outcome but {@code DONE}, the reason, worded for a supervisor. */
public record CommandResult(CommandOutcome outcome, String reason) {
}
