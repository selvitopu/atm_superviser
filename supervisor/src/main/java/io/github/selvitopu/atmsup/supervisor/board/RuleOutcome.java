package io.github.selvitopu.atmsup.supervisor.board;

import java.util.List;

/** Result of one service rule; status is OK, DEGRADED or FAILED, never UNKNOWN. */
public record RuleOutcome(ServiceStatus status, boolean redundancyLost, List<String> reasons) {

    public RuleOutcome {
        if (status == ServiceStatus.UNKNOWN) {
            throw new IllegalArgumentException("A rule outcome is never UNKNOWN");
        }
        reasons = List.copyOf(reasons);
    }
}
