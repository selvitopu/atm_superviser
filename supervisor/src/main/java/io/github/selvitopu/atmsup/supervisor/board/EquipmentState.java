package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.mib.Role;
import java.time.Instant;

/**
 * Durable state of one device. {@code unknownReason} is non-null iff health is UNKNOWN; {@code healthSince} is
 * null only on the initial board, before any event.
 */
public record EquipmentState(String id, Health health, UnknownReason unknownReason, boolean maintenance, Role role,
                             Instant healthSince, Contact contact) {

    public EquipmentState {
        if ((health == Health.UNKNOWN) != (unknownReason != null)) {
            throw new IllegalArgumentException("unknownReason must be set exactly when health is UNKNOWN: "
                    + id + " " + health + " " + unknownReason);
        }
    }
}
