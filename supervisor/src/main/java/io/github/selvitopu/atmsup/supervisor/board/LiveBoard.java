package io.github.selvitopu.atmsup.supervisor.board;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The board plus live-only state. Generations are coordination state owned by the board writer; never stored. The
 * status generation ({@link #generation}) moves on a trap or a contact change and gates full-poll results; the contact
 * generation ({@link #contactGeneration}) moves on a contact change only and gates probe results.
 */
public record LiveBoard(Board board, Map<String, Telemetry> telemetry, Map<String, Long> generations,
                        Map<String, Long> contactGenerations) {

    public LiveBoard {
        telemetry = Collections.unmodifiableMap(new LinkedHashMap<>(telemetry));
        generations = Collections.unmodifiableMap(new LinkedHashMap<>(generations));
        contactGenerations = Collections.unmodifiableMap(new LinkedHashMap<>(contactGenerations));
    }

    public long generation(String equipmentId) {
        return generations.getOrDefault(equipmentId, 0L);
    }

    public long contactGeneration(String equipmentId) {
        return contactGenerations.getOrDefault(equipmentId, 0L);
    }
}
