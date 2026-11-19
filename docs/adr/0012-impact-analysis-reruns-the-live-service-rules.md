# ADR-0012: Impact Analysis Reruns the Live Service Rules

* **Status:** Accepted — Implemented after `v0.1.1`
* **Date:** 2026-11-19
* **Deciders:** Project maintainers

## Context and Problem Statement

The board shows what *is*: each service's status and whether it has lost redundancy. A System Controller also asks
two questions about what *would be*: which single unit, if it failed now, would cost a service or its redundancy
(N-1), and what is left of each service if some units go into maintenance. Until now the controller worked this out
in their head from the service rules in the [domain primer](../domain.md). How should the supervisor answer, without
ever contradicting its own board?

## Decision Drivers

* **One implementation of the service rules.** A second model of the centre could disagree with the board, and an
  answer that contradicts the board is worse than no answer.
* **`UNKNOWN` is never read as healthy.** The answer has to carry the same doubt the board does.
* **Cheap and read-only.** The answer must not touch the board writer and must be fast enough to refresh on its own.
* **No verdict that reads as a clearance.** No result is worded as a clearance or an approval, and
  `NO SERVICE IMPACT` is never shown in the OK green.

## Considered Options

1. **Rerun the live service rules over a hypothetical availability set.** Ask the service evaluator to evaluate the
   board with extra units counted as unavailable, and compare.
2. **A declared dependency graph with cut analysis.** Declare the topology of the centre in the inventory and compute
   which sets of units cut which service.
3. **Sandbox simulation.** Clone the simulator, apply the faults and watch what the supervisor reports.

## Decision Outcome

Chosen option: **Option 1.**

* **One evaluator.** `ServiceEvaluator` gains two public, pure methods with no side effects.
  `assess(board, alsoUnavailable)` returns both passes of every rule (unknown counted as available, then as
  unavailable) with the extra units counted as unavailable. `evaluateWorld(board, down, assumedUp)` returns every
  rule's outcome in one possible state of the centre, where an `UNKNOWN` unit works only if it is in `assumedUp`.
  `evaluate` keeps its behaviour, and the analyzer calls nothing else, so no service rule exists outside `board`.
* **Both sides of every comparison come from the evaluator.** The plan compares the board now with the board with
  the plan's units out. The N-1 table compares the board with the plan applied with that plus one more unit out. A
  difference can therefore only come from the units removed, never from the stored services. The statuses shown
  come from `assess`, as on the board, so a plan's `after` on a fully known board is what the board will show.
* **Three verdicts.** `NO_SERVICE_IMPACT` (nothing changes), `REDUCED_MARGIN` (no service is newly failed, but one
  gets worse) and `SERVICE_LOSS` (a service that was not failed becomes failed). The overall verdict is the worst
  across services. A service that is already failed contributes `NO_SERVICE_IMPACT`.
* **`UNKNOWN` gives the worst case.** The analyzer compares before and after in every possible state of the
  `UNKNOWN` units, each working or not, through `evaluateWorld`. A verdict is the worst over those states, and it
  is flagged unconfirmed when they disagree; the report lists the units in `UNKNOWN`. The unit asked about, or
  planned, counts as working before, because "if it failed" and "take it out" presume that it works. Comparing
  the optimistic and the pessimistic pass alone is not enough: with two `UNKNOWN` units, both passes can agree on
  `NO_SERVICE_IMPACT` for different reasons while a possible state loses the service.
* **A cap on the states.** Up to 10 `UNKNOWN` units, the states are enumerated exactly (at most 1024 per question).
  Beyond that, a service that the question changes in either pass, or whose passes disagree, compares its best
  possible before with its worst possible after and is flagged unconfirmed. Under monotone rules this bound can
  only overstate.
* **A new `impact` module** (`ImpactAnalyzer`) depends only on `board`. [`ImpactController`] in `api` serves
  `GET /api/v1/impact?maintenance=<id>` to every signed-in role. An id that is not in the inventory is a 400 problem
  detail.
* **Screens.** The Impact screen (`/impact`, linked for every role) has a planner whose selection lives in the URL,
  the plan's verdict, a table of single points of failure, a refresh every 5 s, the time the answer is based on and
  the basis line "Derived from the supervised equipment and the service rules; not a safety assessment". The confirm
  bar of **Maintenance on** shows the same verdict as a preview. Confirm is never blocked.
* **Pair notes.** The rules do not read roles. For a planned unit that is the active unit of its pair, the plan adds
  a note that maintenance switches the pair over to its partner, or that the partner cannot take over. The note
  states a domain fact; it never changes the verdict.

### Consequences

* **Good:** the analysis can never drift from the board, because it is the board's rules. A property test shows that
  `assess` with no extra unit agrees with `evaluate`, and an end-to-end test shows that the plan's prediction equals
  what the board shows once the unit is in maintenance.
* **Good:** it is exact for whatever the board itself believes, and fast: units times rules, microseconds. New rules
  (VOICE, REC, NET in `v0.2`) are covered as they are registered, at no extra cost.
* **Trade-off:** it is only as rich as the rules. The rules count available units, so the analysis does not model
  the brief interruption of a switchover, a radar's coverage geometry or a degraded active unit. The basis line says
  so, and the verdict is not a safety assessment.
* **Trade-off:** the preview informs and does not block. Planned maintenance sometimes accepts reduced margin, so
  interlocks stay planned (hazard H11).
* **Not done:** storing or scheduling plans, N-2 and beyond, and single-point markers on the board tiles.

### Why not the others

* **Dependency graph:** it builds a second model of the centre that can disagree with the service rules, so the
  board and the analysis could contradict each other.
* **Sandbox simulation:** it takes seconds per unit, exists only in demo mode, and answers about the simulator
  rather than the real board.

## Verification

* [`ServiceEvaluatorAssessTest`] — `assess` agrees with `evaluate` when nothing extra is unavailable, an extra unit
  counts as unavailable, `UNKNOWN` still splits the passes, and `evaluateWorld` settles each `UNKNOWN` unit, with
  no unit up giving the pessimistic pass and all up the optimistic one.
* [`ImpactAnalyzerTest`] — the demo scenarios, `UNKNOWN` as an unconfirmed worst case over every possible state
  (including two or more `UNKNOWN` units and a centre that is all `UNKNOWN`), the bound above the cap never better
  than the exact answer, the pair notes and the order of the table.
* [`ImpactControllerTest`] — the roles, the 400 for an unknown unit, and duplicates ignored.
* [`SupervisorEndToEndIT`] — `thePredictedImpactOfMaintenanceIsWhatTheBoardShows`.

[`ImpactController`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/ImpactController.java
[`ServiceEvaluatorAssessTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluatorAssessTest.java
[`ImpactAnalyzerTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/impact/ImpactAnalyzerTest.java
[`ImpactControllerTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/api/ImpactControllerTest.java
[`SupervisorEndToEndIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorEndToEndIT.java
