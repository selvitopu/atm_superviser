package io.github.selvitopu.atmsup.supervisor.board;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The board plus live-only state. Generations are coordination state owned by the board writer; never stored. */
public record LiveBoard(Board board, Map<String, Telemetry> telemetry, Map<String, Long> generations) {

    public LiveBoard {
        telemetry = Collections.unmodifiableMap(new LinkedHashMap<>(telemetry));
        generations = Collections.unmodifiableMap(new LinkedHashMap<>(generations));
    }

    public long generation(String equipmentId) {
        return generations.getOrDefault(equipmentId, 0L);
    }
}
