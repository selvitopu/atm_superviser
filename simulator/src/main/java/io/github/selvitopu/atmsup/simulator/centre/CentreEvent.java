package io.github.selvitopu.atmsup.simulator.centre;

import io.github.selvitopu.atmsup.simulator.model.DeviceNotification;

/** Something the agent host must do as a result of a change in the centre. */
public sealed interface CentreEvent {

    /** The device announces {@code n} to the supervisor. */
    record Notify(String deviceId, DeviceNotification n) implements CentreEvent {
    }

    /** The device starts ({@code on}) or stops answering requests. */
    record Responding(String deviceId, boolean on) implements CentreEvent {
    }

    /** The device starts ({@code on}) or stops dropping its notifications. */
    record DropTraps(String deviceId, boolean on) implements CentreEvent {
    }

    /** The device is about to restart: its agent goes away shortly. */
    record RestartStarted(String deviceId) implements CentreEvent {
    }

    /** The device has restarted: a fresh agent starts. */
    record RestartCompleted(String deviceId) implements CentreEvent {
    }
}
