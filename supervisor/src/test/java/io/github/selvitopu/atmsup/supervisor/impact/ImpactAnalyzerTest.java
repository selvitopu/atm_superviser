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

    private static final List<String> ALL = List.of("RAD-N", "RAD-E", "RAD-S", "SDPS-A", "SDPS-B", "FDPS-A", "FDPS-B");

    private final Inventory inv = inventory();
    private final ServiceEvaluator evaluator =
            new ServiceEvaluator(inv, List.of(new SurveillanceRule(), new FlightDataRule()));
    private final ImpactAnalyzer analyzer = new ImpactAnalyzer(inv, evaluator);

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

    /** These units UNKNOWN, the others OPERATIONAL. */
    private Board unknown(String... ids) {
        Map<String, Health> health = new LinkedHashMap<>();
        for (String id : ids) {
            health.put(id, Health.UNKNOWN);
        }
        return board(health);
    }

    private static ServiceChange service(ImpactReport r, ServiceId id) {
        return r.plan().services().stream().filter(c -> c.id() == id).findFirst().orElseThrow();
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
        assertThat(p.unconfirmed()).isFalse();
        ServiceChange c = change(p, ServiceId.FD);
        assertThat(c.before()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(c.beforeBestCase()).isNull();
        assertThat(c.after()).isEqualTo(ServiceStatus.FAILED);
        assertThat(c.afterBestCase()).isNull();
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
        assertThat(c.after()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(c.afterBestCase()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(c.redundancyLostAfter()).isFalse();
        assertThat(c.reasonsAfter()).containsExactly("No FDPS unit available");
        assertThat(r.unknownUnits()).containsExactly("FDPS-A");
    }

    @Test
    void anUnknownUnitAskedAboutCountsAsWorkingBeforeItFails() {
        ImpactReport r = analyzer.analyze(board(Map.of("FDPS-A", Health.UNKNOWN)), Set.of());

        SinglePoint p = point(r, "FDPS-A");
        assertThat(p.verdict()).isEqualTo(Verdict.REDUCED_MARGIN);
        assertThat(p.unconfirmed()).isFalse();
    }

    @Test
    void unknownSurveillanceUnitsMakeTheLastConfirmedRadarASinglePoint() {
        // the Maintenance on preview for RAD-N while the SDPS pair, RAD-E and RAD-S do not answer
        Board b = unknown("SDPS-A", "SDPS-B", "RAD-E", "RAD-S");

        ImpactReport r = analyzer.analyze(b, Set.of("RAD-N"));
        assertThat(r.plan().verdict()).isEqualTo(Verdict.SERVICE_LOSS);
        assertThat(r.plan().unconfirmed()).isTrue();
        ServiceChange surv = service(r, ServiceId.SURV);
        assertThat(surv.verdict()).isEqualTo(Verdict.SERVICE_LOSS);
        assertThat(surv.before()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(surv.beforeBestCase()).isEqualTo(ServiceStatus.OK);
        assertThat(surv.after()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(surv.afterBestCase()).isEqualTo(ServiceStatus.OK);
        ServiceChange fd = service(r, ServiceId.FD);
        assertThat(fd.verdict()).isEqualTo(Verdict.NO_SERVICE_IMPACT);
        assertThat(fd.beforeBestCase()).isNull();
        assertThat(fd.afterBestCase()).isNull();

        SinglePoint radN = point(analyzer.analyze(b, Set.of()), "RAD-N");
        assertThat(radN.verdict()).isEqualTo(Verdict.SERVICE_LOSS);
        assertThat(radN.unconfirmed()).isTrue();
    }

    @Test
    void twoUnknownRadarsMakeEachOfThemReduceTheMargin() {
        Board b = unknown("RAD-E", "RAD-S");

        ImpactReport r = analyzer.analyze(b, Set.of());
        for (String radar : List.of("RAD-E", "RAD-S")) {
            assertThat(point(r, radar).verdict()).isEqualTo(Verdict.REDUCED_MARGIN);
            assertThat(point(r, radar).unconfirmed()).isTrue();
        }
        ImpactReport plan = analyzer.analyze(b, Set.of("RAD-E"));
        assertThat(plan.plan().verdict()).isEqualTo(Verdict.REDUCED_MARGIN);
        assertThat(plan.plan().unconfirmed()).isTrue();
    }

    @Test
    void anUnknownPairMakesEachOfItsUnitsASinglePoint() {
        ImpactReport r = analyzer.analyze(unknown("FDPS-A", "FDPS-B"), Set.of());

        for (String fdps : List.of("FDPS-A", "FDPS-B")) {
            assertThat(point(r, fdps).verdict()).isEqualTo(Verdict.SERVICE_LOSS);
            assertThat(point(r, fdps).unconfirmed()).isTrue();
        }
    }

    @Test
    void aCentreThatIsAllUnknownGivesEveryRadarTheWorstCase() {
        ImpactReport r = analyzer.analyze(unknown(ALL.toArray(String[]::new)), Set.of());

        for (String radar : List.of("RAD-N", "RAD-E", "RAD-S")) {
            assertThat(point(r, radar).verdict()).isEqualTo(Verdict.SERVICE_LOSS);
            assertThat(point(r, radar).unconfirmed()).isTrue();
        }
        assertThat(r.unknownUnits()).containsExactlyElementsOf(ALL);
    }

    @Test
    void aboveTheCapTheBoundOverstatesAndIsUnconfirmed() {
        // with the SDPS pair UNKNOWN, losing one of three known radars changes nothing in any possible state
        Board b = unknown("SDPS-A", "SDPS-B");
        SinglePoint exact = point(analyzer.analyze(b, Set.of()), "RAD-N");
        assertThat(exact.verdict()).isEqualTo(Verdict.NO_SERVICE_IMPACT);
        assertThat(exact.unconfirmed()).isFalse();

        SinglePoint bound = point(new ImpactAnalyzer(inv, evaluator, 1).analyze(b, Set.of()), "RAD-N");
        assertThat(bound.verdict()).isEqualTo(Verdict.SERVICE_LOSS);
        assertThat(bound.unconfirmed()).isTrue();
    }

    @Test
    void aboveTheCapTheBoundIsNeverBetterThanTheExactAnswer() {
        ImpactAnalyzer bounded = new ImpactAnalyzer(inv, evaluator, 1);
        List<Board> boards = List.of(unknown("SDPS-A", "SDPS-B", "RAD-E", "RAD-S"), unknown("RAD-E", "RAD-S"),
                unknown("FDPS-A", "FDPS-B"), unknown(ALL.toArray(String[]::new)), unknown("SDPS-A", "SDPS-B"),
                board(Map.of("RAD-N", Health.FAILED, "SDPS-A", Health.UNKNOWN, "RAD-S", Health.UNKNOWN)));
        List<Set<String>> plans = List.of(Set.of(), Set.of("RAD-N"), Set.of("RAD-E"), Set.of("FDPS-B"),
                Set.of("RAD-N", "SDPS-A"));
        for (Board b : boards) {
            for (Set<String> plan : plans) {
                ImpactReport exact = analyzer.analyze(b, plan);
                ImpactReport bound = bounded.analyze(b, plan);
                assertNeverBetter(bound.plan().verdict(), bound.plan().unconfirmed(),
                        exact.plan().verdict(), exact.plan().unconfirmed());
                for (int i = 0; i < exact.plan().services().size(); i++) {
                    assertThat(bound.plan().services().get(i).verdict())
                            .isGreaterThanOrEqualTo(exact.plan().services().get(i).verdict());
                }
                for (SinglePoint p : exact.singlePoints()) {
                    SinglePoint q = point(bound, p.unit());
                    assertNeverBetter(q.verdict(), q.unconfirmed(), p.verdict(), p.unconfirmed());
                }
            }
        }
    }

    private static void assertNeverBetter(Verdict bound, boolean boundUnconfirmed, Verdict exact,
                                          boolean exactUnconfirmed) {
        assertThat(bound).isGreaterThanOrEqualTo(exact);
        if (exactUnconfirmed) {
            assertThat(boundUnconfirmed).isTrue();
        }
    }

    @Test
    void aNewReasonAloneReducesTheMargin() {
        ImpactReport r = analyzer.analyze(board(Map.of("SDPS-A", Health.FAILED)), Set.of("RAD-N", "RAD-E"));

        assertThat(r.plan().verdict()).isEqualTo(Verdict.REDUCED_MARGIN);
        assertThat(r.plan().unconfirmed()).isFalse();
        ServiceChange surv = service(r, ServiceId.SURV);
        assertThat(surv.verdict()).isEqualTo(Verdict.REDUCED_MARGIN);
        assertThat(surv.before()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(surv.after()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(surv.redundancyLostAfter()).isTrue();
        assertThat(surv.reasonsAfter())
                .containsExactly("Only one SDPS unit available", "Fewer than 2 radars available");
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
