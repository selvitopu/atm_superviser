package io.github.selvitopu.atmsup.supervisor.board;

import java.time.Duration;

/**
 * When an alarm flood starts and ends: it starts at {@code start} counted raises within {@code window} and ends when
 * fewer than {@code end} remain.
 */
public record AlarmFloodPolicy(Duration window, int start, int end) {

    public static final AlarmFloodPolicy DEFAULT = new AlarmFloodPolicy(Duration.ofMinutes(10), 10, 5);

    public AlarmFloodPolicy {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("The flood window must be positive: " + window);
        }
        if (start < 1) {
            throw new IllegalArgumentException("The flood start must be at least 1: " + start);
        }
        if (end < 0 || end > start) {
            throw new IllegalArgumentException("The flood end must be between 0 and the start: " + end);
        }
    }
}
