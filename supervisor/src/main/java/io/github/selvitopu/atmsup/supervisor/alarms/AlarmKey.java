package io.github.selvitopu.atmsup.supervisor.alarms;

/** Identity of an alarm: one open alarm per (equipment, code, instance). */
public record AlarmKey(String equipmentId, int code, int instance) {

    public String openKey() {
        return equipmentId + "|" + code + "|" + instance;
    }
}
