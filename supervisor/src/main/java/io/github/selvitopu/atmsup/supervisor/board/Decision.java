package io.github.selvitopu.atmsup.supervisor.board;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the board writer must do for one input: commit {@code events}, merge {@code telemetry}, bump the generation of
 * every {@code invalidated} device and ask for a follow-up poll of every {@code repoll} request. A {@code stale}
 * decision has no effect at all.
 */
public record Decision(List<DomainEvent> events, Map<String, Telemetry> telemetry, List<BoardEvents.RepollRequested> repoll,
                       Set<String> invalidated, boolean stale, BoardResult result) {

    private static final Decision STALE = new Decision(List.of(), Map.of(), List.of(), Set.of(), true,
            BoardResult.ok());

    public Decision {
        events = List.copyOf(events);
        telemetry = Collections.unmodifiableMap(new LinkedHashMap<>(telemetry));
        repoll = List.copyOf(repoll);
        invalidated = Collections.unmodifiableSet(new LinkedHashSet<>(invalidated));
    }

    /**
     * A result from an older generation: no events, telemetry, repoll or invalidation. (Not {@code stale()}: that is
     * the accessor of the {@code stale} component.)
     */
    public static Decision discarded() {
        return STALE;
    }
}
