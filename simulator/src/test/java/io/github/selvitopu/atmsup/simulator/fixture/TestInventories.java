package io.github.selvitopu.atmsup.simulator.fixture;

import io.github.selvitopu.atmsup.inventory.DeviceProperties;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Group;
import io.github.selvitopu.atmsup.inventory.InventoryProperties;
import io.github.selvitopu.atmsup.inventory.SnmpProperties;
import io.github.selvitopu.atmsup.inventory.TrapTarget;
import io.github.selvitopu.atmsup.inventory.UserCredentials;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The demo inventory and credentials for tests, on ports chosen by the test. */
public final class TestInventories {

    public static final String HOST = "127.0.0.1";

    private TestInventories() {
    }

    /** The 7 devices of {@code config/inventory.yml} on consecutive ports from {@code firstAgentPort}, host 127.0.0.1. */
    public static InventoryProperties v01(int firstAgentPort, int trapPort) {
        List<DeviceProperties> devices = new ArrayList<>();
        devices.add(device("RAD-N", DeviceType.RADAR, "Radar North", Group.SURVEILLANCE, firstAgentPort, null));
        devices.add(device("RAD-E", DeviceType.RADAR, "Radar East", Group.SURVEILLANCE, firstAgentPort + 1, null));
        devices.add(device("RAD-S", DeviceType.RADAR, "Radar South", Group.SURVEILLANCE, firstAgentPort + 2, null));
        devices.add(device("SDPS-A", DeviceType.SDPS, "Surveillance Data Processing A", Group.SURVEILLANCE,
                firstAgentPort + 3, "SDPS-B"));
        devices.add(device("SDPS-B", DeviceType.SDPS, "Surveillance Data Processing B", Group.SURVEILLANCE,
                firstAgentPort + 4, "SDPS-A"));
        devices.add(device("FDPS-A", DeviceType.FDPS, "Flight Data Processing A", Group.FLIGHT_DATA,
                firstAgentPort + 5, "FDPS-B"));
        devices.add(device("FDPS-B", DeviceType.FDPS, "Flight Data Processing B", Group.FLIGHT_DATA,
                firstAgentPort + 6, "FDPS-A"));
        return new InventoryProperties("Demo ACC", HOST, Duration.ofSeconds(5), new TrapTarget(HOST, trapPort),
                devices);
    }

    /** The properties in {@code atm.inventory.*} form, for {@code @DynamicPropertySource} in Spring tests. */
    public static Map<String, String> asProperties(InventoryProperties p) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("atm.inventory.centre", p.centre());
        map.put("atm.inventory.default-host", p.defaultHost());
        map.put("atm.inventory.default-poll-interval", p.defaultPollInterval().toSeconds() + "s");
        map.put("atm.inventory.trap-target.host", p.trapTarget().host());
        map.put("atm.inventory.trap-target.port", String.valueOf(p.trapTarget().port()));
        for (int i = 0; i < p.devices().size(); i++) {
            DeviceProperties d = p.devices().get(i);
            String prefix = "atm.inventory.devices[" + i + "].";
            map.put(prefix + "id", d.id());
            map.put(prefix + "type", d.type().name());
            map.put(prefix + "name", d.name());
            map.put(prefix + "group", d.group().name());
            map.put(prefix + "port", String.valueOf(d.port()));
            if (d.host() != null) {
                map.put(prefix + "host", d.host());
            }
            if (d.pair() != null) {
                map.put(prefix + "pair", d.pair());
            }
        }
        return map;
    }

    /** The shipped demo passwords. */
    public static SnmpProperties demoCredentials() {
        return new SnmpProperties(new UserCredentials("monitor-auth-demo", "monitor-priv-demo"),
                new UserCredentials("operator-auth-demo", "operator-priv-demo"));
    }

    private static DeviceProperties device(String id, DeviceType type, String name, Group group, int port,
                                           String pair) {
        return new DeviceProperties(id, type, name, group, null, port, pair, null);
    }
}
