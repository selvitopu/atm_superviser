package io.github.selvitopu.atmsup.supervisor.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GapRuleTest {

    private static final Instant T0 = Instant.parse("2026-01-01T14:00:00Z");
    private static final String HEARTBEAT = "SUPERVISOR_HEARTBEAT";

    private final GapRule rule = new GapRule(Duration.ofSeconds(90));

    @Test
    void noEventIsNoData() {
        assertThat(rule.noDataAt(T0, null, null)).isTrue();
        assertThat(rule.threshold()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void withinTheThresholdIsData() {
        assertThat(rule.noDataAt(T0.plusSeconds(60), T0, HEARTBEAT)).isFalse();
        assertThat(rule.noDataAt(T0, T0, HEARTBEAT)).isFalse();
    }

    @Test
    void exactlyTheThresholdIsNoData() {
        assertThat(rule.noDataAt(T0.plusSeconds(90), T0, HEARTBEAT)).as("data only in (at - 90 s, at]").isTrue();
        assertThat(rule.noDataAt(T0.plusSeconds(90).minusMillis(1), T0, HEARTBEAT)).isFalse();
    }

    @Test
    void pastTheThresholdIsNoData() {
        assertThat(rule.noDataAt(T0.plusSeconds(91), T0, HEARTBEAT)).isTrue();
    }

    @Test
    void aStopStartsTheGapAtOnce() {
        assertThat(rule.noDataAt(T0, T0, "SUPERVISOR_STOPPING")).isTrue();
        assertThat(rule.noDataAt(T0.plusSeconds(1), T0, "SUPERVISOR_STOPPING")).isTrue();
        assertThat(rule.noDataAt(T0.plusSeconds(1), T0, "SUPERVISOR_STARTED")).isFalse();
    }
}
