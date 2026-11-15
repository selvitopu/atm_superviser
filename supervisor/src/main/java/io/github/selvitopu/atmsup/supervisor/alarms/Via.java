package io.github.selvitopu.atmsup.supervisor.alarms;

/** What caused an alarm change; {@code RESYNC} is the reconciliation of a full poll's alarm table walk. */
public enum Via {
    TRAP, SUPERVISOR, OPERATOR, RESYNC
}
