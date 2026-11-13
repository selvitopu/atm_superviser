package io.github.selvitopu.atmsup.supervisor.alarms;

/** Service state relevant to alarms. A null flag means unknown. */
public record ServiceAlarmInput(String serviceId, String serviceName, Boolean failed, Boolean redundancyLost) {
}
