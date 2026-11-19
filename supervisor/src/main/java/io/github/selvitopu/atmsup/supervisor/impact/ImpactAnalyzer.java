package io.github.selvitopu.atmsup.supervisor.impact;

import io.github.selvitopu.atmsup.inventory.Device;
import io.github.selvitopu.atmsup.inventory.Inventory;
import io.github.selvitopu.atmsup.mib.Role;
import io.github.selvitopu.atmsup.supervisor.board.Assessment;
import io.github.selvitopu.atmsup.supervisor.board.Board;
import io.github.selvitopu.atmsup.supervisor.board.EquipmentState;
import io.github.selvitopu.atmsup.supervisor.board.Health;
import io.github.selvitopu.atmsup.supervisor.board.RuleOutcome;
import io.github.selvitopu.atmsup.supervisor.board.ServiceEvaluator;
import io.github.selvitopu.atmsup.supervisor.board.ServiceId;
import io.github.selvitopu.atmsup.supervisor.board.ServiceStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What-if analysis over a board. Read-only; every service answer comes from {@link ServiceEvaluator#assess}. */
public final class ImpactAnalyzer {

    private final Inventory inv;
    private final ServiceEvaluator evaluator;

    public ImpactAnalyzer(Inventory inv, ServiceEvaluator evaluator) {
        this.inv = inv;
        this.evaluator = evaluator;
    }

    /** The plan must hold inventory ids only; the caller validates. */
    public ImpactReport analyze(Board board, Set<String> plan) {
        Map<ServiceId, Assessment> baseline = evaluator.assess(board, Set.of());
        Map<ServiceId, Assessment> planned = evaluator.assess(board, plan);
        Compared planCompared = compare(baseline, planned);

        List<String> planUnits = new ArrayList<>();
        List<OutOfService> out = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<SinglePoint> points = new ArrayList<>();
        for (Device d : inv.devices()) {
            String id = d.id();
            EquipmentState s = board.equipment().get(id);
            if (plan.contains(id)) {
                planUnits.add(id);
            }
            if (s.health() == Health.UNKNOWN) {
                unknown.add(id);
            }
            if (s.health() == Health.FAILED) {
                out.add(new OutOfService(id, OutReason.FAILED));
            } else if (s.maintenance()) {
                out.add(new OutOfService(id, OutReason.MAINTENANCE));
            } else if (plan.contains(id)) {
                out.add(new OutOfService(id, OutReason.PLANNED));
            } else {
                Set<String> withUnit = new LinkedHashSet<>(plan);
                withUnit.add(id);
                Compared c = compare(planned, evaluator.assess(board, withUnit));
                points.add(new SinglePoint(id, s.health(), c.verdict, c.unconfirmed, c.changes));
            }
        }
        points.sort(Comparator.comparing((SinglePoint p) -> p.verdict()).reversed());
        PlanResult result = new PlanResult(planUnits, planCompared.verdict, planCompared.unconfirmed,
                planCompared.changes, notes(board, plan));
        return new ImpactReport(board.lastEventId(), result, points, out, unknown);
    }

    private List<String> notes(Board board, Set<String> plan) {
        List<String> notes = new ArrayList<>();
        for (Device d : inv.devices()) {
            EquipmentState s = board.equipment().get(d.id());
            if (!plan.contains(d.id()) || s.health() == Health.FAILED || s.maintenance()
                    || s.role() != Role.ACTIVE || d.pair() == null) {
                continue;
            }
            EquipmentState partner = board.equipment().get(d.pair());
            boolean takesOver = (partner.health() == Health.OPERATIONAL || partner.health() == Health.DEGRADED)
                    && !partner.maintenance() && !plan.contains(d.pair());
            notes.add(takesOver
                    ? d.id() + " is the active unit of its pair: maintenance switches the pair over to " + d.pair()
                    : d.id() + " is the active unit of its pair, and " + d.pair() + " cannot take over");
        }
        return notes;
    }

    private record Compared(Verdict verdict, boolean unconfirmed, List<ServiceChange> changes) {
    }

    private static Compared compare(Map<ServiceId, Assessment> before, Map<ServiceId, Assessment> after) {
        Verdict worst = Verdict.NO_SERVICE_IMPACT;
        boolean unconfirmed = false;
        List<ServiceChange> changes = new ArrayList<>();
        for (Map.Entry<ServiceId, Assessment> e : after.entrySet()) {
            Assessment b = before.get(e.getKey());
            Assessment a = e.getValue();
            Verdict optimistic = pass(b.optimistic(), a.optimistic());
            Verdict pessimistic = pass(b.pessimistic(), a.pessimistic());
            Verdict verdict = optimistic.compareTo(pessimistic) >= 0 ? optimistic : pessimistic;
            unconfirmed |= optimistic != pessimistic;
            worst = worst.compareTo(verdict) >= 0 ? worst : verdict;
            if (verdict != Verdict.NO_SERVICE_IMPACT) {
                boolean afterConfirmed = a.confirmed();
                changes.add(new ServiceChange(e.getKey(), verdict,
                        b.confirmed() ? b.optimistic().status() : ServiceStatus.UNKNOWN,
                        b.confirmed() ? null : b.optimistic().status(),
                        afterConfirmed ? a.optimistic().status() : ServiceStatus.UNKNOWN,
                        afterConfirmed ? null : a.optimistic().status(),
                        afterConfirmed && a.optimistic().redundancyLost(),
                        afterConfirmed ? a.optimistic().reasons() : a.pessimistic().reasons()));
            }
        }
        return new Compared(worst, unconfirmed, changes);
    }

    private static Verdict pass(RuleOutcome before, RuleOutcome after) {
        if (before.status() == ServiceStatus.FAILED) {
            return Verdict.NO_SERVICE_IMPACT;
        }
        if (after.status() == ServiceStatus.FAILED) {
            return Verdict.SERVICE_LOSS;
        }
        boolean worse = after.status().ordinal() > before.status().ordinal();
        boolean lost = !before.redundancyLost() && after.redundancyLost();
        boolean newReason = !before.reasons().containsAll(after.reasons());
        return worse || lost || newReason ? Verdict.REDUCED_MARGIN : Verdict.NO_SERVICE_IMPACT;
    }
}
