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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;

/**
 * What-if analysis over a board. Read-only; every service answer comes from {@link ServiceEvaluator}. A verdict is
 * the worst over every possible state of the UNKNOWN units, and unconfirmed when those states disagree.
 */
public final class ImpactAnalyzer {

    /** The most UNKNOWN units whose states are enumerated by default: at most 1024 states per question. */
    private static final int MAX_UNKNOWN = 10;

    private static final BinaryOperator<Verdict> WORSE = BinaryOperator.maxBy(Comparator.naturalOrder());
    private static final BinaryOperator<Verdict> BETTER = BinaryOperator.minBy(Comparator.naturalOrder());

    private final Inventory inv;
    private final ServiceEvaluator evaluator;
    private final int maxUnknown;

    public ImpactAnalyzer(Inventory inv, ServiceEvaluator evaluator) {
        this(inv, evaluator, MAX_UNKNOWN);
    }

    /** Above {@code maxUnknown} UNKNOWN units, a verdict is a conservative bound instead of exact. */
    public ImpactAnalyzer(Inventory inv, ServiceEvaluator evaluator, int maxUnknown) {
        if (maxUnknown < 0 || maxUnknown > 20) {
            throw new IllegalArgumentException("maxUnknown must be between 0 and 20: " + maxUnknown);
        }
        this.inv = inv;
        this.evaluator = evaluator;
        this.maxUnknown = maxUnknown;
    }

    /** The plan must hold inventory ids only; the caller validates. */
    public ImpactReport analyze(Board board, Set<String> plan) {
        Map<ServiceId, Assessment> baseline = evaluator.assess(board, Set.of());
        Map<ServiceId, Assessment> planned = evaluator.assess(board, plan);
        List<String> undecided = new ArrayList<>();
        for (Device d : inv.devices()) {
            EquipmentState s = board.equipment().get(d.id());
            if (s.health() == Health.UNKNOWN && !s.maintenance()) {
                undecided.add(d.id());
            }
        }
        Compared planCompared = compare(board, new Question(Set.of(), plan, plan, undecided), baseline, planned,
                false);

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
                Compared c = compare(board, new Question(plan, withUnit, Set.of(id), undecided), planned,
                        evaluator.assess(board, withUnit), true);
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

    /**
     * One what-if: the units down before and after, and the units asked about, which count as working before whatever
     * their state ("if it failed" and "take it out" presume they work now). The open units are the UNKNOWN ones whose
     * state is left open; every unit down after is taken out of them.
     */
    private record Question(Set<String> downBefore, Set<String> downAfter, Set<String> asked, List<String> open) {

        Question {
            open = open.stream().filter(id -> !downAfter.contains(id)).toList();
        }
    }

    private record Judged(Verdict verdict, boolean unconfirmed) {
    }

    private record Compared(Verdict verdict, boolean unconfirmed, List<ServiceChange> changes) {
    }

    /** The verdicts come from the possible states; the statuses shown come from the two passes, as on the board. */
    private Compared compare(Board board, Question q, Map<ServiceId, Assessment> before,
                             Map<ServiceId, Assessment> after, boolean onlyChanged) {
        Map<ServiceId, Judged> judged = q.open().size() <= maxUnknown
                ? exact(board, q) : bound(board, q, before, after);
        Verdict worst = Verdict.NO_SERVICE_IMPACT;
        boolean unconfirmed = false;
        List<ServiceChange> changes = new ArrayList<>();
        for (Map.Entry<ServiceId, Assessment> e : after.entrySet()) {
            Assessment b = before.get(e.getKey());
            Assessment a = e.getValue();
            Judged j = judged.get(e.getKey());
            worst = WORSE.apply(worst, j.verdict());
            unconfirmed |= j.unconfirmed();
            if (!onlyChanged || j.verdict() != Verdict.NO_SERVICE_IMPACT) {
                boolean afterConfirmed = a.confirmed();
                changes.add(new ServiceChange(e.getKey(), j.verdict(),
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

    /** Every possible state of the open units: each service's worst verdict, unconfirmed when the states differ. */
    private Map<ServiceId, Judged> exact(Board board, Question q) {
        Map<ServiceId, Verdict> worst = new LinkedHashMap<>();
        Map<ServiceId, Verdict> best = new LinkedHashMap<>();
        List<String> open = q.open();
        for (int state = 0; state < 1 << open.size(); state++) {
            Set<String> up = new HashSet<>(q.asked());
            for (int i = 0; i < open.size(); i++) {
                if ((state >> i & 1) == 1) {
                    up.add(open.get(i));
                }
            }
            Map<ServiceId, RuleOutcome> before = evaluator.evaluateWorld(board, q.downBefore(), up);
            Map<ServiceId, RuleOutcome> after = evaluator.evaluateWorld(board, q.downAfter(), up);
            after.forEach((id, outcome) -> {
                Verdict v = verdict(before.get(id), outcome);
                worst.merge(id, v, WORSE);
                best.merge(id, v, BETTER);
            });
        }
        Map<ServiceId, Judged> out = new LinkedHashMap<>();
        worst.forEach((id, v) -> out.put(id, new Judged(v, v != best.get(id))));
        return out;
    }

    /**
     * Too many open units to enumerate. A service that the asked-about units change in either pass, or whose passes
     * disagree before or after, compares its best possible before (every open unit up) with its worst possible after
     * (every open unit down), and is unconfirmed. Under monotone rules this can only overstate. Every other service
     * keeps its two-pass verdict.
     */
    private Map<ServiceId, Judged> bound(Board board, Question q, Map<ServiceId, Assessment> before,
                                         Map<ServiceId, Assessment> after) {
        Set<String> allUp = new HashSet<>(q.asked());
        allUp.addAll(q.open());
        Map<ServiceId, RuleOutcome> bestBefore = evaluator.evaluateWorld(board, q.downBefore(), allUp);
        Map<ServiceId, RuleOutcome> worstAfter = evaluator.evaluateWorld(board, q.downAfter(), Set.of());
        Map<ServiceId, Judged> out = new LinkedHashMap<>();
        for (ServiceId id : after.keySet()) {
            Assessment b = before.get(id);
            Assessment a = after.get(id);
            boolean changed = !b.optimistic().equals(a.optimistic()) || !b.pessimistic().equals(a.pessimistic());
            boolean disagree = !b.optimistic().equals(b.pessimistic()) || !a.optimistic().equals(a.pessimistic());
            if (changed || disagree) {
                out.put(id, new Judged(verdict(bestBefore.get(id), worstAfter.get(id)), true));
            } else {
                Verdict optimistic = verdict(b.optimistic(), a.optimistic());
                Verdict pessimistic = verdict(b.pessimistic(), a.pessimistic());
                out.put(id, new Judged(WORSE.apply(optimistic, pessimistic), optimistic != pessimistic));
            }
        }
        return out;
    }

    /** One service before and after, in one state: a service already FAILED before has nothing more to lose. */
    private static Verdict verdict(RuleOutcome before, RuleOutcome after) {
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
