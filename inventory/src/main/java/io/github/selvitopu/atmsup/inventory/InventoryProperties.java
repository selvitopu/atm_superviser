package io.github.selvitopu.atmsup.inventory;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Raw binding of the {@code atm.inventory} section. Use {@link Inventory#from} to validate it. */
@ConfigurationProperties("atm.inventory")
public record InventoryProperties(String centre, String defaultHost,
                                  @DefaultValue("5s") Duration defaultPollInterval,
                                  TrapTarget trapTarget, List<DeviceProperties> devices) {
}
