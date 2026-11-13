package io.github.selvitopu.atmsup.simulator.model;

import io.github.selvitopu.atmsup.mib.Severity;

/** An alarm a device wants to be active right now; {@link AlarmTable} turns a set of these into notifications. */
public record AlarmSpec(int code, int instance, String instanceName, Severity severity, String text) {
}
