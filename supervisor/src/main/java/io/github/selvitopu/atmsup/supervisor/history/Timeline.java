package io.github.selvitopu.atmsup.supervisor.history;

import java.time.Instant;
import java.util.List;

/**
 * What the Replay timeline marks over a window: the raised alarms (at most {@link TimelineQuery#MAX_MARKERS}, the
 * newest kept, in time order), the alarm floods, the gaps in the recording and the supervisor restarts.
 *
 * @param truncated true when more alarms were raised in the window than the markers hold
 */
public record Timeline(List<Marker> markers, boolean truncated, List<Span> floods, List<Span> gaps,
                       List<Instant> restarts) {

    public Timeline {
        markers = List.copyOf(markers);
        floods = List.copyOf(floods);
        gaps = List.copyOf(gaps);
        restarts = List.copyOf(restarts);
    }

    /** One alarm raise, as stored. */
    public record Marker(Instant at, String equipmentId, int code, String severity, String text, boolean suppressed) {
    }

    /**
     * A stretch of time; {@code end} is null when it had not ended by the end of the window. A span that began before
     * the window is reported with its true start, not clipped to it: the screen clips when it draws.
     */
    public record Span(Instant start, Instant end) {
    }
}
