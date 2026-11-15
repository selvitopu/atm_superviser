package io.github.selvitopu.atmsup.supervisor.snmp;

import io.github.selvitopu.atmsup.mib.Severity;
import java.time.Instant;

/** One row of the device's {@code atmTsAlarmTable}; {@code instanceName} is empty when the device has none. */
public record DeviceAlarm(int code, int instance, String instanceName, Severity severity, String text,
                          Instant raisedAt) {
}
