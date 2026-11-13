package io.github.selvitopu.atmsup.supervisor.snmp;

import java.time.Instant;

/** The answer to a lightweight probe: the device is reachable and its agent has been up for this long. */
public record ProbeObservation(String equipmentId, Instant observedAt, long sysUpTimeCentis) {
}
