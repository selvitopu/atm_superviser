package io.github.selvitopu.atmsup.supervisor.alarms;

/** The type is CLOSED whenever the resulting alarm state is CLOSED. */
public record AlarmChange(AlarmEventType type, Alarm alarm, Via via, String actor) {
}
