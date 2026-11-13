package io.github.selvitopu.atmsup.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.FileSystemResource;

class InventoryTest {

    private static Binder binderFor(String path) {
        try {
            MutablePropertySources sources = new MutablePropertySources();
            new YamlPropertySourceLoader().load("inventory", new FileSystemResource(path)).forEach(sources::addLast);
            return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InventoryProperties bind(String path) {
        return binderFor(path).bind("atm.inventory", InventoryProperties.class).get();
    }

    private static DeviceProperties dev(String id, DeviceType type, int port, String pair) {
        return new DeviceProperties(id, type, "Name " + id, Group.SURVEILLANCE, null, port, pair, null);
    }

    private static InventoryProperties broken() {
        return new InventoryProperties("Demo ACC", "localhost", Duration.ofSeconds(5), new TrapTarget("localhost", 10162),
                List.of(dev("RAD-N", DeviceType.RADAR, 16101, null),
                        dev("RAD-N", DeviceType.RADAR, 16101, null),
                        dev("SDPS-A", DeviceType.SDPS, 16104, "RAD-N"),
                        dev("FDPS-A", DeviceType.FDPS, 16106, null)));
    }

    @Test
    void bindsTheSharedInventoryFile() {
        Inventory inv = Inventory.from(bind("../config/inventory.yml"));
        assertThat(inv.centre()).isEqualTo("Demo ACC");
        assertThat(inv.devices()).extracting(Device::id)
                .containsExactly("RAD-N", "RAD-E", "RAD-S", "SDPS-A", "SDPS-B", "FDPS-A", "FDPS-B");
        assertThat(inv.device("SDPS-A").host()).isEqualTo("localhost");
        assertThat(inv.device("RAD-N").pollInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(inv.startsActive("SDPS-A")).isTrue();
        assertThat(inv.startsActive("SDPS-B")).isFalse();
        assertThat(inv.partner("FDPS-B")).map(Device::id).contains("FDPS-A");
        assertThat(inv.partner("RAD-N")).isEmpty();
        assertThat(inv.byType(DeviceType.RADAR)).hasSize(3);
        assertThat(inv.trapTarget()).isEqualTo(new TrapTarget("localhost", 10162));
    }

    @Test
    void bindsSnmpCredentialsWithDemoDefaults() {
        SnmpProperties snmp = binderFor("../config/inventory.yml").bind("atm.snmp", SnmpProperties.class).get();
        assertThat(snmp.monitor().authPassword()).isEqualTo("monitor-auth-demo");
        assertThat(snmp.operator().privPassword()).isEqualTo("operator-priv-demo");
    }

    @Test
    void reportsAllProblemsAtOnce() {
        assertThatThrownBy(() -> Inventory.from(broken())).isInstanceOf(InventoryException.class)
                .hasMessageContaining("Duplicate id RAD-N")
                .hasMessageContaining("Duplicate port localhost:16101")
                .hasMessageContaining("SDPS-A: pair RAD-N has a different type")
                .hasMessageContaining("FDPS-A: SDPS/FDPS needs a pair");
    }

    @Test
    void rejectsBadIdPortAndName() {
        InventoryProperties p = new InventoryProperties("Demo ACC", "localhost", Duration.ofSeconds(5),
                new TrapTarget("localhost", 10162),
                List.of(new DeviceProperties("bad id", DeviceType.RADAR, " ", Group.SURVEILLANCE, null, 80, null, null)));
        assertThatThrownBy(() -> Inventory.from(p)).isInstanceOf(InventoryException.class)
                .hasMessageContaining("Invalid id bad id")
                .hasMessageContaining("port 80 out of range")
                .hasMessageContaining("name must not be blank");
    }

    @Test
    void rejectsPairThatDoesNotPointBack() {
        InventoryProperties p = new InventoryProperties("Demo ACC", "localhost", Duration.ofSeconds(5),
                new TrapTarget("localhost", 10162),
                List.of(dev("SDPS-A", DeviceType.SDPS, 16104, "SDPS-B"), dev("SDPS-B", DeviceType.SDPS, 16105, "SDPS-B")));
        assertThatThrownBy(() -> Inventory.from(p)).isInstanceOf(InventoryException.class)
                .hasMessageContaining("SDPS-A: pair SDPS-B does not point back");
    }

    @Test
    void unknownDeviceIsRejected() {
        Inventory inv = Inventory.from(bind("../config/inventory.yml"));
        assertThatThrownBy(() -> inv.device("X-1")).hasMessage("Unknown equipment X-1");
        assertThat(inv.find("X-1")).isEmpty();
    }

    @Test
    void shortPasswordRejected() {
        assertThatThrownBy(() -> new UserCredentials("short", "long-enough")).hasMessageContaining("at least 8");
    }
}
