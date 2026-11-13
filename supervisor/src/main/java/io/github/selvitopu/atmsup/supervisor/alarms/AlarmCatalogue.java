package io.github.selvitopu.atmsup.supervisor.alarms;

import io.github.selvitopu.atmsup.mib.Severity;

/** Supervisor-originated alarms. */
public final class AlarmCatalogue {

    public static final int LOST_CONTACT = 901;
    public static final Severity LOST_CONTACT_SEVERITY = Severity.MAJOR;
    public static final String LOST_CONTACT_TEXT = "Lost contact";

    public static final int REDUNDANCY_LOST = 903;
    public static final Severity REDUNDANCY_LOST_SEVERITY = Severity.MAJOR;

    public static final int SERVICE_FAILED = 904;
    public static final Severity SERVICE_FAILED_SEVERITY = Severity.CRITICAL;

    private AlarmCatalogue() {
    }

    public static String redundancyLostText(String serviceName) {
        return "Service redundancy lost: " + serviceName;
    }

    public static String serviceFailedText(String serviceName) {
        return "Service failed: " + serviceName;
    }
}
