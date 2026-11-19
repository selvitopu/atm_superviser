package io.github.selvitopu.atmsup.supervisor.control;

/**
 * What the event log records of a command: its intent before the SET is sent, then what became of it. Every command
 * is recorded as {@code ISSUED} first and as one of the other outcomes after.
 */
public enum CommandOutcome {
    /** Recorded before the SET is sent; a command is never sent unless this record is stored. */
    ISSUED,
    /** The unit accepted it. */
    DONE,
    /** The unit refused it as inconsistent with its state, e.g. a restart outside maintenance mode. */
    REFUSED,
    /** The unit did not answer within the SNMP timeout: the command may or may not have been applied. */
    NO_ANSWER,
    /** The unit could not act on it: the operator user's credentials or access, or the SET never left. */
    FAILED,
    /** Its intent could not be stored in time, so it was never sent. */
    NOT_SENT
}
