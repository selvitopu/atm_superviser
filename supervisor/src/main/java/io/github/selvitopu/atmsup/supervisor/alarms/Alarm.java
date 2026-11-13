package io.github.selvitopu.atmsup.supervisor.alarms;

import io.github.selvitopu.atmsup.mib.Severity;
import java.time.Instant;

public record Alarm(String id, AlarmKey key, String instanceName, Severity severity, String text,
                    AlarmSource source, AlarmState state, boolean suppressed, Instant raisedAt,
                    Instant ackedAt, String ackedBy, String ackComment, Instant clearedAt) {
}
