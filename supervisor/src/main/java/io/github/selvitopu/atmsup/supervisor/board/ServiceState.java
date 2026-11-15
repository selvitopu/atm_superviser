package io.github.selvitopu.atmsup.supervisor.board;

import java.time.Instant;
import java.util.List;

/**
 * {@code bestCase} is non-null iff status is UNKNOWN: the status if every unknown device turned out available.
 * {@code redundancyUnconfirmed} is true when the two passes agree on the status but not on the redundancy flag: the
 * shown {@code redundancyLost} is the confirmed (optimistic) value, and alarm 903 is neither raised nor cleared.
 * It is never true for an UNKNOWN service. Event payloads stored before it existed decode it as false.
 */
public record ServiceState(ServiceId id, ServiceStatus status, ServiceStatus bestCase, boolean redundancyLost,
                           boolean redundancyUnconfirmed, List<String> reasons, Instant since) {

    public ServiceState {
        if ((status == ServiceStatus.UNKNOWN) != (bestCase != null)) {
            throw new IllegalArgumentException("bestCase must be set exactly when status is UNKNOWN: " + id);
        }
        if (redundancyUnconfirmed && status == ServiceStatus.UNKNOWN) {
            throw new IllegalArgumentException("an UNKNOWN service has no redundancy flag to confirm: " + id);
        }
        reasons = List.copyOf(reasons);
    }
}
