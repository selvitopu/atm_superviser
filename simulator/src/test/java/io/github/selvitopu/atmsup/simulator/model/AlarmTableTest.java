package io.github.selvitopu.atmsup.simulator.model;

import static io.github.selvitopu.atmsup.mib.Severity.CRITICAL;
import static io.github.selvitopu.atmsup.mib.Severity.MAJOR;
import static io.github.selvitopu.atmsup.mib.Severity.MINOR;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.mib.Severity;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmCleared;
import io.github.selvitopu.atmsup.simulator.model.DeviceNotification.AlarmRaised;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlarmTableTest {

    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant t1 = t0.plusSeconds(10);
    private final AlarmTable t = new AlarmTable();

    private static AlarmSpec spec(int code, Severity severity) {
        return new AlarmSpec(code, 0, "", severity, "alarm " + code);
    }

    private static ActiveAlarm active(int code, Severity severity, Instant raisedAt) {
        return new ActiveAlarm(code, 0, "", severity, "alarm " + code, raisedAt);
    }

    @Test
    void raiseThenClear() {
        assertThat(t.reconcile(List.of(spec(101, CRITICAL)), t0))
                .containsExactly(new AlarmRaised(active(101, CRITICAL, t0)));
        assertThat(t.reconcile(List.of(), t1)).containsExactly(new AlarmCleared(101, 0));
        assertThat(t.active()).isEmpty();
    }

    @Test
    void severityChangeReRaisesKeepingRaisedAt() {
        t.reconcile(List.of(spec(201, MINOR)), t0);
        assertThat(t.reconcile(List.of(spec(201, MAJOR)), t1))
                .containsExactly(new AlarmRaised(active(201, MAJOR, t0)));
        assertThat(t.active()).containsExactly(active(201, MAJOR, t0));
    }

    @Test
    void unchangedDesiredSetEmitsNothing() {
        t.reconcile(List.of(spec(101, CRITICAL)), t0);
        assertThat(t.reconcile(List.of(spec(101, CRITICAL)), t1)).isEmpty();
    }

    @Test
    void clearsComeFirstThenRaisesBothSortedByCodeAndInstance() {
        t.reconcile(List.of(spec(202, MINOR), spec(101, CRITICAL), spec(102, MINOR)), t0);
        List<DeviceNotification> out = t.reconcile(List.of(spec(204, MINOR), spec(201, MINOR), spec(102, MINOR)), t1);
        assertThat(out).containsExactly(
                new AlarmCleared(101, 0), new AlarmCleared(202, 0),
                new AlarmRaised(active(201, MINOR, t1)), new AlarmRaised(active(204, MINOR, t1)));
        assertThat(t.active()).extracting(ActiveAlarm::code).containsExactly(102, 201, 204);
    }
}
