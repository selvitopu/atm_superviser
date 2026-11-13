package io.github.selvitopu.atmsup.supervisor.board;

import java.time.Instant;
import java.util.List;

/** {@code bestCase} is non-null iff status is UNKNOWN: the status if every unknown device turned out available. */
public record ServiceState(ServiceId id, ServiceStatus status, ServiceStatus bestCase, boolean redundancyLost,
                           List<String> reasons, Instant since) {

    public ServiceState {
        if ((status == ServiceStatus.UNKNOWN) != (bestCase != null)) {
            throw new IllegalArgumentException("bestCase must be set exactly when status is UNKNOWN: " + id);
        }
        reasons = List.copyOf(reasons);
    }
}
