package io.github.selvitopu.atmsup.supervisor.board;

/** Why a device's health is UNKNOWN. */
public enum UnknownReason {
    NOT_YET_POLLED,
    LOST_CONTACT,
    /** Contact was restored by a probe, but no full poll has confirmed health yet. */
    AWAITING_STATUS,
    /** Probes succeed, but no full poll has succeeded for more than 3 x the poll interval. */
    STATUS_UNAVAILABLE,
    RESTARTING,
    SUPERVISOR_RESTARTED
}
