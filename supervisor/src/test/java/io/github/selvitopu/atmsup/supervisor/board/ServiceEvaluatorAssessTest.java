package io.github.selvitopu.atmsup.supervisor.board;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.inventory.Inventory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ServiceEvaluatorAssessTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final Inventory inv = BoardFixtures.inventory(false);
    private final ServiceEvaluator evaluator =
            new ServiceEvaluator(inv, List.of(new SurveillanceRule(), new FlightDataRule()));

    private Board board(Map<String, Object> overrides, Set<String> maintenance) {
        Board b = Board.initial(inv, List.of());
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.selvitopu.atmsup.supervisor.board.ServiceEvaluatorTest#rows")
    void withNothingExtraItAgreesWithEvaluate(String name, Map<String, Object> overrides, Set<String> maintenance,
                                              ServiceStatus survStatus, ServiceStatus survBest,
                                              boolean survRedundancyLost, boolean survRedundancyUnconfirmed,
                                              List<String> survReasons, ServiceStatus fdStatus,
                                              boolean fdRedundancyLost) {
        Board b = board(overrides, maintenance);
        var evaluated = evaluator.evaluate(b, NOW);
        var assessed = evaluator.assess(b, Set.of());
        assertThat(assessed.keySet()).containsExactly(ServiceId.SURV, ServiceId.FD);
        for (ServiceId id : assessed.keySet()) {
            var state = evaluated.get(id);
            var a = assessed.get(id);
            assertThat(a.confirmed()).isEqualTo(state.status() != ServiceStatus.UNKNOWN);
            if (a.confirmed()) {
                assertThat(a.optimistic().status()).isEqualTo(state.status());
                assertThat(a.optimistic().redundancyLost()).isEqualTo(state.redundancyLost());
            }
        }
    }

    @Test
    void anExtraUnitCountsAsUnavailable() {
        var fd = evaluator.assess(board(Map.of(), Set.of()), Set.of("FDPS-B")).get(ServiceId.FD);
        assertThat(fd.optimistic().status()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(fd.pessimistic().status()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(fd.optimistic().redundancyLost()).isTrue();
        assertThat(fd.optimistic().reasons()).containsExactly("Only one FDPS unit available");
    }

    @Test
    void anExtraUnknownUnitIsUnavailableInBothPasses() {
        var fd = evaluator.assess(board(Map.of("FDPS-A", UnknownReason.NOT_YET_POLLED), Set.of()),
                Set.of("FDPS-A", "FDPS-B")).get(ServiceId.FD);
        assertThat(fd.confirmed()).isTrue();
        assertThat(fd.optimistic().status()).isEqualTo(ServiceStatus.FAILED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.selvitopu.atmsup.supervisor.board.ServiceEvaluatorTest#rows")
    void aWorldWithNoUnknownUnitUpIsThePessimisticPassAndWithAllUpTheOptimistic(
            String name, Map<String, Object> overrides, Set<String> maintenance, ServiceStatus survStatus,
            ServiceStatus survBest, boolean survRedundancyLost, boolean survRedundancyUnconfirmed,
            List<String> survReasons, ServiceStatus fdStatus, boolean fdRedundancyLost) {
        Board b = board(overrides, maintenance);
        Set<String> unknown = overrides.keySet().stream()
                .filter(id -> overrides.get(id) == UnknownReason.NOT_YET_POLLED).collect(Collectors.toSet());
        var assessed = evaluator.assess(b, Set.of());
        var none = evaluator.evaluateWorld(b, Set.of(), Set.of());
        var all = evaluator.evaluateWorld(b, Set.of(), unknown);
        assertThat(none.keySet()).containsExactly(ServiceId.SURV, ServiceId.FD);
        for (ServiceId id : assessed.keySet()) {
            assertThat(none.get(id)).isEqualTo(assessed.get(id).pessimistic());
            assertThat(all.get(id)).isEqualTo(assessed.get(id).optimistic());
        }
    }

    @Test
    void aWorldSettlesEachUnknownUnitAndDownWins() {
        Board b = board(Map.of("FDPS-A", UnknownReason.NOT_YET_POLLED, "FDPS-B", UnknownReason.NOT_YET_POLLED),
                Set.of());
        assertThat(evaluator.evaluateWorld(b, Set.of(), Set.of()).get(ServiceId.FD).status())
                .isEqualTo(ServiceStatus.FAILED);
        assertThat(evaluator.evaluateWorld(b, Set.of(), Set.of("FDPS-A")).get(ServiceId.FD).status())
                .isEqualTo(ServiceStatus.DEGRADED);
        assertThat(evaluator.evaluateWorld(b, Set.of(), Set.of("FDPS-A", "FDPS-B")).get(ServiceId.FD).status())
                .isEqualTo(ServiceStatus.OK);
        assertThat(evaluator.evaluateWorld(b, Set.of("FDPS-A"), Set.of("FDPS-A", "FDPS-B")).get(ServiceId.FD)
                .status()).isEqualTo(ServiceStatus.DEGRADED);
    }

    @Test
    void assumingAUnitUpNeverCountsAFailedOrMaintainedUnitAsWorking() {
        Board b = board(Map.of("FDPS-A", Health.FAILED), Set.of("FDPS-B"));
        assertThat(evaluator.evaluateWorld(b, Set.of(), Set.of("FDPS-A", "FDPS-B")).get(ServiceId.FD).status())
                .isEqualTo(ServiceStatus.FAILED);
    }

    @Test
    void unknownStillSplitsThePasses() {
        var fd = evaluator.assess(board(Map.of("FDPS-A", UnknownReason.NOT_YET_POLLED), Set.of()),
                Set.of("FDPS-B")).get(ServiceId.FD);
        assertThat(fd.optimistic().status()).isEqualTo(ServiceStatus.DEGRADED);
        assertThat(fd.pessimistic().status()).isEqualTo(ServiceStatus.FAILED);
        assertThat(fd.confirmed()).isFalse();
    }
}
