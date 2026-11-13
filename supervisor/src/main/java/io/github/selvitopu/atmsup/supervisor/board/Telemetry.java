package io.github.selvitopu.atmsup.supervisor.board;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live-only measurements and counters of one device; never stored.
 *
 * @param lastPollAt          last successful full poll
 * @param lastProbeAt         last successful contact (a probe or a full poll)
 * @param consecutiveFailures consecutive failed probes
 * @param fullPollFailures    consecutive failed full polls
 * @param lastSampleAt        when the last TELEMETRY_SAMPLED was written
 */
public record Telemetry(Instant lastPollAt, Instant lastProbeAt, int consecutiveFailures, int fullPollFailures,
                        Instant lastSampleAt, Map<String, Object> values,
                        Map<String, List<Map<String, Object>>> tables) {

    public static final Telemetry EMPTY = new Telemetry(null, null, 0, 0, null, Map.of(), Map.of());

    public Telemetry {
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        tables = tables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }
}
