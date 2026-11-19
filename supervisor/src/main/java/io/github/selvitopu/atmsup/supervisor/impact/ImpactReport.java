package io.github.selvitopu.atmsup.supervisor.impact;

import java.util.List;

/** The plan's effect and the single points of failure, from the board at {@code lastEventId}. */
public record ImpactReport(long lastEventId, PlanResult plan, List<SinglePoint> singlePoints,
                           List<OutOfService> outOfService, List<String> unknownUnits) {

    public ImpactReport {
        singlePoints = List.copyOf(singlePoints);
        outOfService = List.copyOf(outOfService);
        unknownUnits = List.copyOf(unknownUnits);
    }
}
