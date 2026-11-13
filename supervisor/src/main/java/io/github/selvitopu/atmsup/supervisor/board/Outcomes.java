package io.github.selvitopu.atmsup.supervisor.board;

import java.util.ArrayList;
import java.util.List;

/** Builds a rule outcome from its FAILED and DEGRADED reasons, worst first. */
final class Outcomes {

    private Outcomes() {
    }

    static RuleOutcome of(List<String> failed, List<String> degraded, boolean redundancyLost) {
        List<String> reasons = new ArrayList<>(failed);
        reasons.addAll(degraded);
        ServiceStatus status = !failed.isEmpty() ? ServiceStatus.FAILED
                : !degraded.isEmpty() ? ServiceStatus.DEGRADED : ServiceStatus.OK;
        return new RuleOutcome(status, redundancyLost, reasons);
    }
}
