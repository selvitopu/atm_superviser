package io.github.selvitopu.atmsup.supervisor.history;

import io.github.selvitopu.atmsup.supervisor.board.EventType;
import java.time.Duration;
import java.time.Instant;

/**
 * Whether the supervisor was recording at an instant. There is no data when no event other than a telemetry sample
 * occurred at or before it, when the newest such event is older than the threshold before it, or when that event is
 * SUPERVISOR_STOPPING. The threshold is 1.5 heartbeat intervals, so a running supervisor always has a heartbeat
 * inside it. The board history, the timeline and the incident summary all use this one rule.
 */
public final class GapRule {

    private final Duration threshold;

    /** @throws IllegalArgumentException if the threshold is null, zero or negative */
    public GapRule(Duration threshold) {
        if (threshold == null || threshold.isZero() || threshold.isNegative()) {
            throw new IllegalArgumentException("The gap threshold must be positive: " + threshold);
        }
        this.threshold = threshold;
    }

    /**
     * @param newestNonSampleEventAt   when the newest event at or before {@code at} that is not TELEMETRY_SAMPLED
     *                                 occurred; null when there is none
     * @param newestNonSampleEventType that event's {@link EventType} name; null when there is none
     */
    public boolean noDataAt(Instant at, Instant newestNonSampleEventAt, String newestNonSampleEventType) {
        return newestNonSampleEventAt == null
                || EventType.SUPERVISOR_STOPPING.name().equals(newestNonSampleEventType)
                || newestNonSampleEventAt.isBefore(at.minus(threshold));
    }

    public Duration threshold() {
        return threshold;
    }
}
