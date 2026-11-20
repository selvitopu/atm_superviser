package io.github.selvitopu.atmsup.supervisor.alarms;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Inventory;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The facts of the inventory that correlation needs: each unit's redundancy partner and the sensor units. */
public record CorrelationTopology(Map<String, String> partners, Set<String> sensors) {

    public CorrelationTopology {
        partners = Map.copyOf(partners);
        sensors = Set.copyOf(sensors);
    }

    public static CorrelationTopology from(Inventory inv) {
        Map<String, String> partners = new HashMap<>();
        Set<String> sensors = new HashSet<>();
        for (Device d : inv.devices()) {
            if (d.pair() != null) {
                partners.put(d.id(), d.pair());
            }
            if (d.type() == DeviceType.RADAR || d.type() == DeviceType.ADSB) {
                sensors.add(d.id());
            }
        }
        return new CorrelationTopology(partners, sensors);
    }
}
