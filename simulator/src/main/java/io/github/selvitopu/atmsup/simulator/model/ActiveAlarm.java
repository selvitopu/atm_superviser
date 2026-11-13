package io.github.selvitopu.atmsup.simulator.model;

import io.github.selvitopu.atmsup.mib.Severity;
import java.time.Instant;

/** An alarm currently raised on a device, identified by {@code (code, instance)}. */
public record ActiveAlarm(int code, int instance, String instanceName, Severity severity, String text,
                          Instant raisedAt) {
}
