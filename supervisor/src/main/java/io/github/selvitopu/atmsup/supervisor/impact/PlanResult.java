package io.github.selvitopu.atmsup.supervisor.impact;

import java.util.List;

/** What the planned maintenance would do; {@code unconfirmed} when UNKNOWN equipment leaves the verdict open. */
public record PlanResult(List<String> units, Verdict verdict, boolean unconfirmed, List<ServiceChange> services,
                         List<String> notes) {

    public PlanResult {
        units = List.copyOf(units);
        services = List.copyOf(services);
        notes = List.copyOf(notes);
    }
}
