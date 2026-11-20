package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.supervisor.board.Board;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The board as it was at {@code at}: the durable board, each unit's newest telemetry sample since the supervisor last
 * started (by equipment id), and whether nothing was being recorded then ({@link GapRule}).
 */
public record ReplayBoard(Instant at, Board board, Map<String, Sample> samples, boolean noData) {

    public ReplayBoard {
        samples = Collections.unmodifiableMap(new LinkedHashMap<>(samples));
    }
}
