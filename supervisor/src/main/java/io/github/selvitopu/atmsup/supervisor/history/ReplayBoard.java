package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.supervisor.board.Board;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The board as it was at {@code at}: the durable board, each unit's newest telemetry sample since the supervisor last
 * started (by equipment id), whether nothing was being recorded then ({@link GapRule}), and the time and type of the
 * newest event other than a sample at or before it (both null when there is none), which the browser needs to apply
 * the same rule while it plays on.
 */
public record ReplayBoard(Instant at, Board board, Map<String, Sample> samples, boolean noData,
                          Instant lastRecordedAt, String lastRecordedType) {

    public ReplayBoard {
        samples = Collections.unmodifiableMap(new LinkedHashMap<>(samples));
    }
}
