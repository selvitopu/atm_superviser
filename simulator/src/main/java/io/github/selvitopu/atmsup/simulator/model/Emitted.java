package io.github.selvitopu.atmsup.simulator.model;

/** A notification together with the device that announced it. */
public record Emitted(String deviceId, DeviceNotification notification) {
}
