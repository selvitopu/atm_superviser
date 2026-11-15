package io.github.selvitopu.atmsup.supervisor.board;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.inventory.Inventory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ServiceEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String UNKNOWN_PREFIX = "Depends on equipment in UNKNOWN state: ";

    private final Inventory inv = BoardFixtures.inventory(false);
    private final ServiceEvaluator evaluator =
            new ServiceEvaluator(inv, List.of(new SurveillanceRule(), new FlightDataRule()));

    /** All seven devices OPERATIONAL, then the given overrides; "maint:" prefix marks maintenance. */
    private Board board(Inventory i, Map<String, Object> overrides, Set<String> maintenance) {
        Board b = Board.initial(i, List.of());
        Map<String, EquipmentState> eq = new LinkedHashMap<>(b.equipment());
        eq.replaceAll((id, s) -> new EquipmentState(id, Health.OPERATIONAL, null, maintenance.contains(id),
                s.role(), NOW, new Contact(true, NOW, null)));
        overrides.forEach((id, v) -> {
            EquipmentState s = eq.get(id);
            boolean m = maintenance.contains(id);
            if (v == UnknownReason.NOT_YET_POLLED) {
                eq.put(id, new EquipmentState(id, Health.UNKNOWN, UnknownReason.NOT_YET_POLLED, m, s.role(), NOW,
                        new Contact(false, null, null)));
            } else {
                eq.put(id, new EquipmentState(id, (Health) v, null, m, s.role(), NOW, s.contact()));
            }
        });
        return new Board(eq, Map.of(), Map.of(), 0);
    }

    private static final UnknownReason UNK = UnknownReason.NOT_YET_POLLED;

    static List<Arguments> rows() {
        return List.of(
                Arguments.of("all operational", Map.of(), Set.of(),
                        ServiceStatus.OK, null, false, false, List.of(), ServiceStatus.OK, false),
                Arguments.of("one radar failed", Map.of("RAD-N", Health.FAILED), Set.of(),
                        ServiceStatus.OK, null, false, false, List.of(), ServiceStatus.OK, false),
                Arguments.of("two radars failed", Map.of("RAD-N", Health.FAILED, "RAD-E", Health.FAILED), Set.of(),
                        ServiceStatus.DEGRADED, null, false, false, List.of("Fewer than 2 radars available"),
                        ServiceStatus.OK, false),
                Arguments.of("all radars failed",
                        Map.of("RAD-N", Health.FAILED, "RAD-E", Health.FAILED, "RAD-S", Health.FAILED), Set.of(),
                        ServiceStatus.FAILED, null, false, false, List.of("No radar available"),
                        ServiceStatus.OK, false),
                Arguments.of("SDPS-A failed", Map.of("SDPS-A", Health.FAILED), Set.of(),
                        ServiceStatus.DEGRADED, null, true, false, List.of("Only one SDPS unit available"),
                        ServiceStatus.OK, false),
                Arguments.of("SDPS-A maintenance, SDPS-B failed", Map.of("SDPS-B", Health.FAILED), Set.of("SDPS-A"),
                        ServiceStatus.FAILED, null, true, false, List.of("No SDPS unit available"),
                        ServiceStatus.OK, false),
                Arguments.of("SDPS-A unknown", Map.of("SDPS-A", UNK), Set.of(),
                        ServiceStatus.UNKNOWN, ServiceStatus.OK, false, false, List.of(UNKNOWN_PREFIX + "SDPS-A"),
                        ServiceStatus.OK, false),
                Arguments.of("SDPS-A unknown, SDPS-B failed", Map.of("SDPS-A", UNK, "SDPS-B", Health.FAILED), Set.of(),
                        ServiceStatus.UNKNOWN, ServiceStatus.DEGRADED, false, false,
                        List.of(UNKNOWN_PREFIX + "SDPS-A"), ServiceStatus.OK, false),
                Arguments.of("all radars failed, SDPS-A unknown",
                        Map.of("RAD-N", Health.FAILED, "RAD-E", Health.FAILED, "RAD-S", Health.FAILED,
                                "SDPS-A", UNK), Set.of(),
                        ServiceStatus.FAILED, null, false, true, List.of("No radar available",
                                "Unconfirmed: Only one SDPS unit available (equipment in UNKNOWN state: SDPS-A)"),
                        ServiceStatus.OK, false),
                Arguments.of("one radar unknown, two operational", Map.of("RAD-N", UNK), Set.of(),
                        ServiceStatus.OK, null, false, false, List.of(), ServiceStatus.OK, false),
                Arguments.of("SDPS-A failed, RAD-N and RAD-E unknown",
                        Map.of("SDPS-A", Health.FAILED, "RAD-N", UNK, "RAD-E", UNK), Set.of(),
                        ServiceStatus.DEGRADED, null, true, false,
                        List.of("Only one SDPS unit available", "Unconfirmed: Fewer than 2 radars available "
                                + "(equipment in UNKNOWN state: RAD-N, RAD-E)"), ServiceStatus.OK, false),
                Arguments.of("RAD-N and RAD-E failed, SDPS-B unknown",
                        Map.of("RAD-N", Health.FAILED, "RAD-E", Health.FAILED, "SDPS-B", UNK), Set.of(),
                        ServiceStatus.DEGRADED, null, false, true,
                        List.of("Fewer than 2 radars available", "Unconfirmed: Only one SDPS unit available "
                                + "(equipment in UNKNOWN state: SDPS-B)"), ServiceStatus.OK, false),
                Arguments.of("no SDPS and no radar lists both, FAILED first",
                        Map.of("SDPS-A", Health.FAILED, "SDPS-B", Health.FAILED, "RAD-N", Health.FAILED,
                                "RAD-E", Health.FAILED, "RAD-S", Health.FAILED), Set.of(),
                        ServiceStatus.FAILED, null, true, false,
                        List.of("No SDPS unit available", "No radar available"), ServiceStatus.OK, false),
                Arguments.of("SDPS-A failed and one radar left lists FAILED-free degraded reasons in order",
                        Map.of("SDPS-A", Health.FAILED, "RAD-N", Health.FAILED, "RAD-E", Health.FAILED), Set.of(),
                        ServiceStatus.DEGRADED, null, true, false,
                        List.of("Only one SDPS unit available", "Fewer than 2 radars available"),
                        ServiceStatus.OK, false),
                Arguments.of("FDPS-B unknown and in maintenance", Map.of("FDPS-B", UNK), Set.of("FDPS-B"),
                        ServiceStatus.OK, null, false, false, List.of(), ServiceStatus.DEGRADED, true),
                Arguments.of("FDPS-A failed, FDPS-B failed", Map.of("FDPS-A", Health.FAILED, "FDPS-B", Health.FAILED),
                        Set.of(), ServiceStatus.OK, null, false, false, List.of(), ServiceStatus.FAILED, true),
                Arguments.of("both unknown FDPS", Map.of("FDPS-A", UNK, "FDPS-B", UNK), Set.of(),
                        ServiceStatus.OK, null, false, false, List.of(), ServiceStatus.UNKNOWN, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void evaluatesServices(String name, Map<String, Object> overrides, Set<String> maintenance,
                           ServiceStatus survStatus, ServiceStatus survBest, boolean survRedundancyLost,
                           boolean survRedundancyUnconfirmed, List<String> survReasons, ServiceStatus fdStatus,
                           boolean fdRedundancyLost) {
        var services = evaluator.evaluate(board(inv, overrides, maintenance), NOW);
        var surv = services.get(ServiceId.SURV);
        assertThat(surv.status()).isEqualTo(survStatus);
        assertThat(surv.bestCase()).isEqualTo(survBest);
        assertThat(surv.redundancyLost()).isEqualTo(survRedundancyLost);
        assertThat(surv.redundancyUnconfirmed()).isEqualTo(survRedundancyUnconfirmed);
        assertThat(surv.reasons()).isEqualTo(survReasons);
        var fd = services.get(ServiceId.FD);
        assertThat(fd.status()).isEqualTo(fdStatus);
        assertThat(fd.redundancyUnconfirmed()).isFalse();
        if (fdStatus != ServiceStatus.UNKNOWN) {
            assertThat(fd.redundancyLost()).isEqualTo(fdRedundancyLost);
        } else {
            assertThat(fd.bestCase()).isEqualTo(ServiceStatus.OK);
            assertThat(fd.reasons()).containsExactly(UNKNOWN_PREFIX + "FDPS-A, FDPS-B");
        }
        assertThat(services.keySet()).containsExactly(ServiceId.SURV, ServiceId.FD);
    }

    @Test
    void everythingNotYetPolledIsUnknownWithOptimisticBestCase() {
        var services = evaluator.evaluate(Board.initial(inv, List.of()), NOW);
        assertThat(services.get(ServiceId.SURV).status()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(services.get(ServiceId.SURV).bestCase()).isEqualTo(ServiceStatus.OK);
        assertThat(services.get(ServiceId.SURV).reasons())
                .containsExactly(UNKNOWN_PREFIX + "RAD-N, RAD-E, RAD-S, SDPS-A, SDPS-B");
        assertThat(services.get(ServiceId.FD).status()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(services.get(ServiceId.FD).bestCase()).isEqualTo(ServiceStatus.OK);
        assertThat(services.get(ServiceId.FD).reasons()).containsExactly(UNKNOWN_PREFIX + "FDPS-A, FDPS-B");
    }

    @Test
    void absentAdsbIsIgnoredAndPresentAdsbDegradesWhenUnavailable() {
        Inventory withAdsb = BoardFixtures.inventory(true);
        var ev = new ServiceEvaluator(withAdsb, List.of(new SurveillanceRule()));
        var ok = ev.evaluate(board(withAdsb, Map.of(), Set.of()), NOW).get(ServiceId.SURV);
        assertThat(ok.status()).isEqualTo(ServiceStatus.OK);
        var failed = ev.evaluate(board(withAdsb, Map.of("ADSB-1", Health.FAILED), Set.of()), NOW)
                .get(ServiceId.SURV);
        assertThat(failed.status()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(failed.reasons()).containsExactly("ADS-B unavailable");
        assertThat(failed.redundancyLost()).isFalse();
    }

    @Test
    void allRadarsFailedWithAdsbAvailableIsDegraded() {
        Inventory withAdsb = BoardFixtures.inventory(true);
        var ev = new ServiceEvaluator(withAdsb, List.of(new SurveillanceRule()));
        var surv = ev.evaluate(board(withAdsb, Map.of("RAD-N", Health.FAILED, "RAD-E", Health.FAILED,
                "RAD-S", Health.FAILED), Set.of()), NOW).get(ServiceId.SURV);
        assertThat(surv.status()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(surv.reasons()).containsExactly("No radar available");
    }

    @Test
    void noRadarAndNoAdsbIsFailed() {
        Inventory withAdsb = BoardFixtures.inventory(true);
        var ev = new ServiceEvaluator(withAdsb, List.of(new SurveillanceRule()));
        var surv = ev.evaluate(board(withAdsb, Map.of("RAD-N", Health.FAILED, "RAD-E", Health.FAILED,
                "RAD-S", Health.FAILED, "ADSB-1", Health.FAILED), Set.of()), NOW).get(ServiceId.SURV);
        assertThat(surv.status()).isEqualTo(ServiceStatus.FAILED);
        assertThat(surv.reasons()).containsExactly("No sensor input available (radar or ADS-B)");
    }

    @Test
    void sinceIsKeptWhileStatusUnchanged() {
        Board b = board(inv, Map.of(), Set.of());
        var first = evaluator.evaluate(b, NOW);
        Board withServices = new Board(b.equipment(), first, b.openAlarms(), 1);
        var later = evaluator.evaluate(withServices, NOW.plusSeconds(60));
        assertThat(later.get(ServiceId.SURV).since()).isEqualTo(NOW);
        assertThat(later.get(ServiceId.FD).since()).isEqualTo(NOW);
    }

    @Test
    void sinceMovesWhenStatusChanges() {
        Board b = board(inv, Map.of(), Set.of());
        Board withServices = new Board(b.equipment(), evaluator.evaluate(b, NOW), b.openAlarms(), 1);
        Board degraded = board(inv, Map.of("SDPS-A", Health.FAILED), Set.of());
        Board next = new Board(degraded.equipment(), withServices.services(), Map.of(), 2);
        var later = evaluator.evaluate(next, NOW.plusSeconds(60));
        assertThat(later.get(ServiceId.SURV).status()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(later.get(ServiceId.SURV).since()).isEqualTo(NOW.plusSeconds(60));
        assertThat(later.get(ServiceId.FD).since()).isEqualTo(NOW);
    }
}
