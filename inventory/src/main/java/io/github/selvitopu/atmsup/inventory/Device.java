package io.github.selvitopu.atmsup.inventory;

import java.time.Duration;

/** A validated device with host and poll interval already resolved from the defaults. */
public record Device(String id, DeviceType type, String name, Group group, String host, int port,
                     String pair, Duration pollInterval) {
}
