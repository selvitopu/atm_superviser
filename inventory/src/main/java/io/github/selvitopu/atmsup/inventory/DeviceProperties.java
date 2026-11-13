package io.github.selvitopu.atmsup.inventory;

import java.time.Duration;

/** One device entry as written in {@code inventory.yml}. Host, pair and pollInterval are optional. */
public record DeviceProperties(String id, DeviceType type, String name, Group group, String host, int port,
                               String pair, Duration pollInterval) {
}
