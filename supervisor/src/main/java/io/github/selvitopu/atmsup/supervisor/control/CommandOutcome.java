package io.github.selvitopu.atmsup.supervisor.control;

/** What became of a command. */
public enum CommandOutcome {
    /** The unit accepted it. */
    DONE,
    /** The unit refused it as inconsistent with its state, e.g. a restart outside maintenance mode. */
    REFUSED,
    /** The unit did not answer within the SNMP timeout and retry. */
    NO_ANSWER,
    /** The supervisor could not send it, e.g. the unit rejected the operator credentials. */
    FAILED
}
