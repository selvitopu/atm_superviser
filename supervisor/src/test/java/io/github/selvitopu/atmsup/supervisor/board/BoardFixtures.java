package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.inventory.DeviceProperties;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Group;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.InventoryProperties;
import io.github.selvitopu.atmsup.inventory.TrapTarget;
import io.github.selvitopu.atmsup.mib.Role;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** The 7-device demo inventory (optionally plus one ADS-B receiver) and state helpers for board tests. */
final class BoardFixtures {

    private BoardFixtures() {
    }

    static Inventory inventory(boolean withAdsb) {
        List<DeviceProperties> d = new ArrayList<>();
        d.add(dev("RAD-N", DeviceType.RADAR, Group.SURVEILLANCE, 16101, null));
        d.add(dev("RAD-E", DeviceType.RADAR, Group.SURVEILLANCE, 16102, null));
        d.add(dev("RAD-S", DeviceType.RADAR, Group.SURVEILLANCE, 16103, null));
        d.add(dev("SDPS-A", DeviceType.SDPS, Group.SURVEILLANCE, 16104, "SDPS-B"));
        d.add(dev("SDPS-B", DeviceType.SDPS, Group.SURVEILLANCE, 16105, "SDPS-A"));
        d.add(dev("FDPS-A", DeviceType.FDPS, Group.FLIGHT_DATA, 16106, "FDPS-B"));
        d.add(dev("FDPS-B", DeviceType.FDPS, Group.FLIGHT_DATA, 16107, "FDPS-A"));
        if (withAdsb) {
            d.add(dev("ADSB-1", DeviceType.ADSB, Group.SURVEILLANCE, 16108, null));
        }
        return Inventory.from(new InventoryProperties("Demo ACC", "127.0.0.1", Duration.ofSeconds(5),
                new TrapTarget("127.0.0.1", 10162), d));
    }

    private static DeviceProperties dev(String id, DeviceType type, Group group, int port, String pair) {
        return new DeviceProperties(id, type, id, group, null, port, pair, null);
    }

    static EquipmentState state(Board b, String id) {
        return b.equipment().get(id);
    }

    static EquipmentState known(String id, Health h) {
        return new EquipmentState(id, h, null, false, Role.STANDALONE, null, new Contact(true, null, null));
    }

    static EquipmentState unknown(String id, UnknownReason r) {
        return new EquipmentState(id, Health.UNKNOWN, r, false, Role.STANDALONE, null, new Contact(false, null, null));
    }
}
