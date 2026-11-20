package io.github.selvitopu.atmsup.supervisor.alarms;

/** Why an alarm is grouped under a failure; decided by the alarm's shape alone. */
public enum CorrelationRule {
    SENSOR_INPUT, SWITCHOVER, SAME_UNIT
}
