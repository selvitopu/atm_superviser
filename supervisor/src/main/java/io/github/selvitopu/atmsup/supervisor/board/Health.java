package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.mib.EquipmentStatus;

/** Supervisor-side health of a device: the reported status plus UNKNOWN, which only the supervisor can say. */
public enum Health {
    OPERATIONAL, DEGRADED, FAILED, UNKNOWN;

    public static Health of(EquipmentStatus status) {
        return switch (status) {
            case OPERATIONAL -> OPERATIONAL;
            case DEGRADED -> DEGRADED;
            case FAILED -> FAILED;
        };
    }
}
