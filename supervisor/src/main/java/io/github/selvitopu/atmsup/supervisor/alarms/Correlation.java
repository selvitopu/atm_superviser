package io.github.selvitopu.atmsup.supervisor.alarms;

/** The link from a correlated alarm to the failure (a DEVICE 101 alarm) it is grouped under. */
public record Correlation(String causeId, String causeEquipmentId, int causeCode, CorrelationRule rule) {
}
