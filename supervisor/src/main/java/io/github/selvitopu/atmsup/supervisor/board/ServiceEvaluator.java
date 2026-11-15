package io.github.selvitopu.atmsup.supervisor.board;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.DeviceType;
import io.github.selvitopu.atmsup.inventory.Inventory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Evaluates every registered rule twice: optimistically (UNKNOWN equipment available) and pessimistically
 * (unavailable). If they disagree the service is UNKNOWN, never green.
 */
public final class ServiceEvaluator {

    private final Inventory inv;
    private final List<ServiceRule> rules;

    public ServiceEvaluator(Inventory inv, List<ServiceRule> rules) {
        this.inv = inv;
        this.rules = List.copyOf(rules);
    }

    /**
     * Only the optimistic pass's detail is confirmed: when both passes agree on the status, the reasons and
     * {@code redundancyLost} come from it, so alarms 903/904 follow confirmed facts only. A redundancy flag the two
     * passes disagree on is marked {@code redundancyUnconfirmed}, which leaves 903 as it is. What the pessimistic pass
     * adds is reported as a single "Unconfirmed" reason.
     */
    public Map<ServiceId, ServiceState> evaluate(Board b, Instant now) {
        Predicate<String> optimistic = id -> availability(b, id) != Availability.UNAVAILABLE;
        Predicate<String> pessimistic = id -> availability(b, id) == Availability.AVAILABLE;
        Map<ServiceId, ServiceState> out = new LinkedHashMap<>();
        for (ServiceRule rule : rules) {
            RuleOutcome best = rule.evaluate(inv, optimistic);
            RuleOutcome worst = rule.evaluate(inv, pessimistic);
            ServiceState prev = b.services().get(rule.id());
            if (best.status() == worst.status()) {
                List<String> reasons = new ArrayList<>(best.reasons());
                List<String> extra = worst.reasons().stream().filter(r -> !best.reasons().contains(r)).toList();
                if (!extra.isEmpty()) {
                    String ids = String.join(", ", dependsOn(b, rule, best, (o, ref) -> !o.equals(ref)));
                    reasons.add("Unconfirmed: " + String.join("; ", extra)
                            + " (equipment in UNKNOWN state: " + ids + ")");
                }
                boolean redundancyUnconfirmed = best.redundancyLost() != worst.redundancyLost();
                out.put(rule.id(), new ServiceState(rule.id(), best.status(), null, best.redundancyLost(),
                        redundancyUnconfirmed, List.copyOf(reasons), since(prev, best.status(), now)));
            } else {
                String ids = String.join(", ", dependsOn(b, rule, best,
                        (o, ref) -> o.status() != ref.status()));
                out.put(rule.id(), new ServiceState(rule.id(), ServiceStatus.UNKNOWN, best.status(), false, false,
                        List.of("Depends on equipment in UNKNOWN state: " + ids),
                        since(prev, ServiceStatus.UNKNOWN, now)));
            }
        }
        return out;
    }

    /**
     * Unknown devices (inventory order) of every type whose uncertainty alone changes the rule's result. Grouping
     * by type keeps thresholds such as "fewer than 2 of 3" visible.
     */
    private List<String> dependsOn(Board b, ServiceRule rule, RuleOutcome best,
                                   BiPredicate<RuleOutcome, RuleOutcome> differs) {
        Set<String> unknown = inv.devices().stream().map(Device::id)
                .filter(id -> availability(b, id) == Availability.UNKNOWN).collect(Collectors.toSet());
        Set<DeviceType> relevant = unknown.stream().map(id -> inv.device(id).type()).distinct()
                .filter(t -> differs.test(rule.evaluate(inv, id -> availability(b, id) != Availability.UNAVAILABLE
                        && !(unknown.contains(id) && inv.device(id).type() == t)), best))
                .collect(Collectors.toSet());
        return inv.devices().stream().filter(d -> unknown.contains(d.id()) && relevant.contains(d.type()))
                .map(Device::id).toList();
    }

    private static Instant since(ServiceState prev, ServiceStatus status, Instant now) {
        return prev != null && prev.status() == status ? prev.since() : now;
    }

    private enum Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }

    private static Availability availability(Board b, String id) {
        EquipmentState s = b.equipment().get(id);
        if (s != null && s.maintenance()) {
            return Availability.UNAVAILABLE;
        }
        if (s == null || s.health() == Health.UNKNOWN) {
            return Availability.UNKNOWN;
        }
        return s.health() == Health.FAILED ? Availability.UNAVAILABLE : Availability.AVAILABLE;
    }
}
