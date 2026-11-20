package io.github.selvitopu.atmsup.supervisor.board;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The board plus live-only state. Generations are coordination state owned by the board writer; never stored. The
 * status generation ({@link #generation}) moves on a trap or a contact change and gates full-poll results; the contact
 * generation ({@link #contactGeneration}) moves on a contact change only and gates probe results. {@code recentRaises}
 * are the instants of the counted alarm raises in the flood window, oldest first.
 */
public record LiveBoard(Board board, Map<String, Telemetry> telemetry, Map<String, Long> generations,
                        Map<String, Long> contactGenerations, List<Instant> recentRaises) {

    public LiveBoard(Board board, Map<String, Telemetry> telemetry, Map<String, Long> generations,
                     Map<String, Long> contactGenerations) {
        this(board, telemetry, generations, contactGenerations, List.of());
    }

    public LiveBoard {
        telemetry = Collections.unmodifiableMap(new LinkedHashMap<>(telemetry));
        generations = Collections.unmodifiableMap(new LinkedHashMap<>(generations));
        contactGenerations = Collections.unmodifiableMap(new LinkedHashMap<>(contactGenerations));
        recentRaises = List.copyOf(recentRaises);
    }

    public long generation(String equipmentId) {
        return generations.getOrDefault(equipmentId, 0L);
    }

    public long contactGeneration(String equipmentId) {
        return contactGenerations.getOrDefault(equipmentId, 0L);
    }
}
