package io.github.selvitopu.atmsup.supervisor.history;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One stored telemetry sample of a unit: its values and tables as polled at {@code observedAt}. Never interpolated. */
public record Sample(String equipmentId, Instant observedAt, Map<String, Object> values,
                     Map<String, List<Map<String, Object>>> tables) {

    public Sample {
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        tables = tables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }
}
