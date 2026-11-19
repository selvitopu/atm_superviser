package io.github.selvitopu.atmsup.supervisor.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.DeviceProperties;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Group;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.inventory.InventoryProperties;
import io.github.selvitopu.atmsup.inventory.TrapTarget;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.Contact;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.FlightDataRule;
import io.github.selvitopu.atmsup.supervisor.board.Health;
import io.github.selvitopu.atmsup.supervisor.board.ServiceEvaluator;
import io.github.selvitopu.atmsup.supervisor.board.ServiceId;
import io.github.selvitopu.atmsup.supervisor.board.ServiceStatus;
import io.github.selvitopu.atmsup.supervisor.board.SurveillanceRule;
import io.github.selvitopu.atmsup.supervisor.board.UnknownReason;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImpactAnalyzerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final Inventory inv = inventory();
    private final ImpactAnalyzer analyzer = new ImpactAnalyzer(inv,
            new ServiceEvaluator(inv, List.of(new SurveillanceRule(), new FlightDataRule())));

    private static Inventory inventory() {
        List<DeviceProperties> d = new ArrayList<>();
        d.add(dev("RAD-N", DeviceType.RADAR, Group.SURVEILLANCE, 16101, null));
        d.add(dev("RAD-E", DeviceType.RADAR, Group.SURVEILLANCE, 16102, null));
        d.add(dev("RAD-S", DeviceType.RADAR, Group.SURVEILLANCE, 16103, null));
        d.add(dev("SDPS-A", DeviceType.SDPS, Group.SURVEILLANCE, 16104, "SDPS-B"));
        d.add(dev("SDPS-B", DeviceType.SDPS, Group.SURVEILLANCE, 16105, "SDPS-A"));
        d.add(dev("FDPS-A", DeviceType.FDPS, Group.FLIGHT_DATA, 16106, "FDPS-B"));
        d.add(dev("FDPS-B", DeviceType.FDPS, Group.FLIGHT_DATA, 16107, "FDPS-A"));
        return Inventory.from(new InventoryProperties("Demo ACC", "127.0.0.1", Duration.ofSeconds(5),
                new TrapTarget("127.0.0.1", 10162), d));
    }

    private static DeviceProperties dev(String id, DeviceType type, Group group, int port, String pair) {
        return new DeviceProperties(id, type, id, group, null, port, pair, null);
    }

    /** All seven devices OPERATIONAL with inventory roles, then the overrides applied. */
    private Board board(Map<String, Health> health, Set<String> maintenance, Map<String, Role> roles) {
        Board b = Board.initial(inv, List.of());
        Map<String, EquipmentState> eq = new LinkedHashMap<>();
        for (Device d : inv.devices()) {
            String id = d.id();
            Health h = health.getOrDefault(id, Health.OPERATIONAL);
            Role role = roles.getOrDefault(id, b.equipment().get(id).role());
            eq.put(id, new EquipmentState(id, h, h == Health.UNKNOWN ? UnknownReason.NOT_YET_POLLED : null,
                    maintenance.contains(id), role, NOW, new Contact(h != Health.UNKNOWN, NOW, null)));
        }
        return new Board(eq, Map.of(), Map.of(), 7);
    }

    private Board board(Map<String, Health> health) {
        return board(health, Set.of(), Map.of());
    }

    private static SinglePoint point(ImpactReport r, String unit) {
        return r.singlePoints().stream().filter(p -> p.unit().equals(unit)).findFirst().orElseThrow();
    }

    private static ServiceChange change(SinglePoint p, ServiceId id) {
        return p.changes().stream().filter(c -> c.id() == id).findFirst().orElseThrow();
    }

    @Test
    void allHealthyRadarsHaveNoImpactAndProcessorsReduceMargin() {
        ImpactReport r = analyzer.analyze(board(Map.of()), Set.of());

        assertThat(r.lastEventId()).isEqualTo(7);
        assertThat(r.plan().verdict()).isEqualTo(Verdict.NO_SERVICE_IMPACT);
        assertThat(r.plan().notes()).isEmpty();
        assertThat(r.plan().services()).extracting(ServiceChange::id, ServiceChange::verdict,
                ServiceChange::before, ServiceChange::after).containsExactly(
                tuple(ServiceId.SURV, Verdict.NO_SERVICE_IMPACT, ServiceStatus.OK, ServiceStatus.OK),
                tuple(ServiceId.FD, Verdict.NO_SERVICE_IMPACT, ServiceStatus.OK, ServiceStatus.OK));
        assertThat(r.singlePoints()).extracting(SinglePoint::unit)
                .containsExactly("SDPS-A", "SDPS-B", "FDPS-A", "FDPS-B", "RAD-N", "RAD-E", "RAD-S");
        for (String radar : List.of("RAD-N", "RAD-E", "RAD-S")) {
            assertThat(point(r, radar).verdict()).isEqualTo(Verdict.NO_SERVICE_IMPACT);
            assertThat(point(r, radar).changes()).isEmpty();
        }
        for (String sdps : List.of("SDPS-A", "SDPS-B")) {
            SinglePoint p = point(r, sdps);
            assertThat(p.verdict()).isEqualTo(Verdict.REDUCED_MARGIN);
            ServiceChange c = change(p, ServiceId.SURV);
            assertThat(c.before()).isEqualTo(ServiceStatus.OK);
            assertThat(c.after()).isEqualTo(ServiceStatus.DEGRADED);
        }
        for (String fdps : List.of("FDPS-A", "FDPS-B")) {
            SinglePoint p = point(r, fdps);
            assertThat(p.verdict()).isEqualTo(Verdict.REDUCED_MARGIN);
            ServiceChange c = change(p, ServiceId.FD);
            assertThat(c.before()).isEqualTo(ServiceStatus.OK);
            assertThat(c.after()).isEqualTo(ServiceStatus.DEGRADED);
            assertThat(c.reasonsAfter()).containsExactly("Only one FDPS unit available");
        }
    }

    @Test
    void aFailedPartnerMakesTheOtherASinglePoint() {
        ImpactReport r = analyzer.analyze(board(Map.of("FDPS-A", Health.FAILED)), Set.of());

        SinglePoint p = point(r, "FDPS-B");
        assertThat(p.verdict()).isEqualTo(Verdict.SERVICE_LOSS);
        ServiceChange c = change(p, ServiceId.FD);
        assertThat(c.before()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(c.after()).isEqualTo(ServiceStatus.FAILED);
        assertThat(c.reasonsAfter()).containsExactly("No FDPS unit available");
        assertThat(r.outOfService()).containsExactly(new OutOfService("FDPS-A", OutReason.FAILED));
    }

    @Test
    void planningTheStandbyReducesMarginAndExposesTheActive() {
        Board b = board(Map.of(), Set.of(), Map.of("FDPS-A", Role.ACTIVE, "FDPS-B", Role.STANDBY));
        ImpactReport r = analyzer.analyze(b, Set.of("FDPS-B"));

        assertThat(r.plan().verdict()).isEqualTo(Verdict.REDUCED_MARGIN);
        assertThat(r.plan().services()).extracting(ServiceChange::id, ServiceChange::verdict,
                ServiceChange::before, ServiceChange::after).containsExactly(
                tuple(ServiceId.SURV, Verdict.NO_SERVICE_IMPACT, ServiceStatus.OK, ServiceStatus.OK),
                tuple(ServiceId.FD, Verdict.REDUCED_MARGIN, ServiceStatus.OK, ServiceStatus.DEGRADED));
        ServiceChange fd = r.plan().services().stream().filter(c -> c.id() == ServiceId.FD).findFirst().orElseThrow();
        assertThat(fd.before()).isEqualTo(ServiceStatus.OK);
        assertThat(fd.after()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(fd.redundancyLostAfter()).isTrue();
        assertThat(point(r, "FDPS-A").verdict()).isEqualTo(Verdict.SERVICE_LOSS);
        assertThat(r.outOfService()).containsExactly(new OutOfService("FDPS-B", OutReason.PLANNED));
    }

    @Test
    void planningTwoRadarsLeavesTheThirdASinglePoint() {
        ImpactReport r = analyzer.analyze(board(Map.of()), Set.of("RAD-N", "RAD-E"));

        assertThat(r.plan().verdict()).isEqualTo(Verdict.REDUCED_MARGIN);
        ServiceChange surv = r.plan().services().stream().filter(c -> c.id() == ServiceId.SURV).findFirst()
                .orElseThrow();
        assertThat(surv.before()).isEqualTo(ServiceStatus.OK);
        assertThat(surv.after()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(surv.reasonsAfter()).containsExactly("Fewer than 2 radars available");
        assertThat(point(r, "RAD-S").verdict()).isEqualTo(Verdict.SERVICE_LOSS);
    }

    @Test
    void anUnknownUnitGivesTheWorstCaseUnconfirmed() {
        ImpactReport r = analyzer.analyze(board(Map.of("FDPS-A", Health.UNKNOWN)), Set.of());

        SinglePoint p = point(r, "FDPS-B");
        assertThat(p.verdict()).isEqualTo(Verdict.SERVICE_LOSS);
        assertThat(p.unconfirmed()).isTrue();
        ServiceChange c = change(p, ServiceId.FD);
        assertThat(c.before()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(c.beforeBestCase()).isEqualTo(ServiceStatus.OK);
        assertThat(r.unknownUnits()).containsExactly("FDPS-A");
    }

    @Test
    void aPlannedUnitThatIsAlreadyFailedChangesNothing() {
        ImpactReport r = analyzer.analyze(board(Map.of("FDPS-B", Health.FAILED)), Set.of("FDPS-B"));

        assertThat(r.plan().verdict()).isEqualTo(Verdict.NO_SERVICE_IMPACT);
        assertThat(r.plan().units()).containsExactly("FDPS-B");
        assertThat(r.outOfService()).containsExactly(new OutOfService("FDPS-B", OutReason.FAILED));
        assertThat(r.plan().notes()).isEmpty();
    }

    @Test
    void anAlreadyFailedServiceIsNoImpact() {
        ImpactReport r = analyzer.analyze(
                board(Map.of("FDPS-A", Health.FAILED, "FDPS-B", Health.FAILED)), Set.of());

        assertThat(r.singlePoints()).allSatisfy(p ->
                assertThat(p.changes()).noneMatch(c -> c.id() == ServiceId.FD));
        assertThat(r.plan().verdict()).isEqualTo(Verdict.NO_SERVICE_IMPACT);
    }

    @Test
    void planningTheActiveUnitNotesTheSwitchover() {
        Map<String, Role> roles = Map.of("FDPS-A", Role.ACTIVE, "FDPS-B", Role.STANDBY);

        ImpactReport switched = analyzer.analyze(board(Map.of(), Set.of(), roles), Set.of("FDPS-A"));
        assertThat(switched.plan().notes()).containsExactly(
                "FDPS-A is the active unit of its pair: maintenance switches the pair over to FDPS-B");

        ImpactReport stuck = analyzer.analyze(board(Map.of(), Set.of("FDPS-B"), roles), Set.of("FDPS-A"));
        assertThat(stuck.plan().notes()).containsExactly(
                "FDPS-A is the active unit of its pair, and FDPS-B cannot take over");
        assertThat(stuck.plan().verdict()).isEqualTo(Verdict.SERVICE_LOSS);
    }
}
