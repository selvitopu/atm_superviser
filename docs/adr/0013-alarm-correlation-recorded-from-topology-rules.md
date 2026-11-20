# ADR-0013: Alarm Correlation Is Recorded When It Is Made, From Fixed Topology Rules

* **Status:** Accepted — Implemented after `v0.1.1`
* **Date:** 2026-11-20
* **Deciders:** Project maintainers

## Context and Problem Statement

One equipment failure produces several alarms at once. When `RAD-N` fails, the supervisor raises five: `101`,
`201` and `202` on the radar, and a `401 Sensor input lost` on each SDPS unit. When `FDPS-A` fails, `FDPS-B` raises
`104 Switchover occurred` and the service raises `903`. Two failed radars give ten alarms in seconds. A list with one
row per alarm leaves the System Controller to work out by hand that the rows are one incident. How should the
supervisor group them, so that replay still shows what the operator saw at the time, and so that grouping never
hides an alarm?

## Decision Drivers

* **Replay shows what the operator saw.** The [target design](../design/2026-11-13-atm-supervisor-design.md) says
  replay shows what operators saw, not what today's rules would compute. The grouping has to be a stored fact.
* **One source of truth.** The event log is the whole story ([ADR-0004](0004-full-state-event-log-and-upsert-reducer.md)).
  A second store of links would be a second thing to fold.
* **Never hide an alarm.** A grouped alarm is still raised, counted, stored and acknowledgeable, and a link is never
  worse than the alarm list without it ([hazard H16](../safety/hazard-log.md#h16-correlation-hides-or-misstates-an-alarm)).
* **Every link can be explained in one sentence.** No scoring and no guessing.
* **Arrival order is irrelevant.** Traps from different agents race, so the SDPS `401` can arrive before the radar's
  `101`.

## Considered Options

1. **Recorded correlation, decided in the pure decider.** A pure `AlarmCorrelator` runs on every alarm change inside
   `BoardDecider`, before the change becomes an event. It sets the alarm's `correlation` and emits the adoption and
   detachment updates.
2. **Correlation computed at read time.** The API or the UI groups open alarms by the same rules.
3. **A separate correlation table.** Incident rows link alarm ids, written beside the alarm events.

## Decision Outcome

Chosen option: **Option 1.**

* **The record.** `Alarm` gains a nullable `Correlation(causeId, causeEquipmentId, causeCode, rule)`. It is part of
  the alarm payload in `ALARM_RAISED` and `ALARM_UPDATED` events and of the `alarm` table (four nullable columns, all
  null or all set). The cause's equipment and code are copied in, so history and replay can say "↳ RAD-N 101" even
  after the cause is closed or purged.
* **Three rules, by shape.** A cause is an open, active `DEVICE` alarm with code `101`. `SENSOR_INPUT` links an
  SDPS `401` for radar X to X's `101`. `SWITCHOVER` links a `104` on a paired unit to its partner's `101`.
  `SAME_UNIT` links any other `DEVICE` alarm (not `101` or `103`) to its own unit's `101`.
* **The shape alone picks the rule.** An alarm's rule never depends on which causes happen to be open. A `401` for
  `RAD-N` on a failed `SDPS-A` is only ever a candidate for `RAD-N`'s `101`, never for `SDPS-A`'s, whichever trap
  arrives first. Whether the cause is open only decides whether a link is made. Without this, arrival order could
  change a link.
* **Adoption.** A cause that arrives after its correlated alarms adopts them: one `ALARM_UPDATED` event each, with
  `via: CORRELATION`, in the same decision as the cause. Both orders end with the same links. A link is never
  re-pointed while its cause is active; it is set once and only ever removed.
* **Detachment.** A correlated alarm is never more severe than its cause. When it escalates above the cause, or the
  cause de-escalates (or is re-raised) below it, the link is removed, again with an update event.
* **Left out on purpose.** Service alarms (`903`, `904`) and lost contact (`901`) are never correlated and never
  causes. A service alarm is the operational impact and usually has several causes, so one link would misstate it.
  Lost contact is not equipment failure, and the supervisor does not guess that a radar site is down.
  There are no chains: the depth is one.
* **The flood.** An alarm flood is about 10 new alarms in 10 minutes, the benchmark in the alarm-management
  standards ISA-18.2 and EEMUA 191. The state starts when the count of raises over the last 10 minutes reaches 10
  and ends when it falls below 5. The 5 is this project's own choice: the gap stops the indicator flapping around one
  threshold. The window, start and end are `atm.alarms.flood.window` (`10m`), `atm.alarms.flood.start` (`10`) and
  `atm.alarms.flood.end` (`5`). Every raise of an alarm that is not suppressed counts, correlated or not: the rate
  measures what arrives, and grouping is how the screen copes with it.
* **Why the state is stored and its window is not.** The flood state is durable: `ALARM_FLOOD_STARTED` and
  `ALARM_FLOOD_ENDED` events (entity kind `FLOOD`), carried on the board and the stream, so replay folds them like
  any other event and never recomputes them. The window of raise times is live-only coordination state, like the
  polling generations. It rides in the decision and is stored only after a successful commit, so a failed commit
  leaves it as it was. It starts empty after a restart, and `SUPERVISOR_STARTED` clears the flood so that the live
  board and the fold of the log agree.
* **Screens.** The alarm list shows one row per cause with `+n correlated` (and `· k unack` when any are
  unacknowledged), collapsed by default. A correlated alarm is listed under its cause only while the cause is active
  and shown. In every other case it is a top-level row. Acknowledging a collapsed group sends the ids of the whole
  group; the server's acknowledgement rules are unchanged. The `ALARM FLOOD` chip and banner show on the board page,
  the only page that runs the live stream. Alarm history shows the stored correlation in a `Correlated` column.
  Nothing recomputes a correlation or a flood at read time.

### Consequences

* **Good:** replay, history and the live list read the same stored fact, and the correlator is a pure class, tested
  without Spring.
* **Good:** an operator can see why an alarm is grouped: the rule's note names the cause unit.
* **Trade-off:** the `Alarm` record gains a component, which touches every constructor call. The payload change is
  additive: payloads written earlier decode with no correlation, and `SCHEMA_VERSION` stays 1.
* **Trade-off:** a late cause costs one update event per adopted alarm.
* **Trade-off:** the rules are code, not configuration. The rule table is the extension point: when switches exist,
  a failed LAN switch can become the cause of its devices' lost-contact alarms.
* **Not done:** probable-cause inference, time-window correlation, an audible alarm (hazard H9 keeps it as planned),
  and a replay screen.

### Why not the others

* **Read-time correlation:** replay would apply today's rules to yesterday's alarms, and the history page could show
  a grouping the live list never did.
* **A separate table:** it is a second source of truth beside the event log. Replay would need another fold, and the
  event log would no longer be the whole story. One nullable component on the alarm says the same thing.

## Verification

* [`AlarmCorrelatorTest`] — `theShapeAloneChoosesTheRule`, `aMoreSevereAlarmIsNotCorrelated`,
  `escalationAboveTheCauseDetaches`, `aCauseThatDeEscalatesDetachesTheMoreSevere`,
  `aReRaisedCauseAtLowerSeverityDetachesTheMoreSevere`, `supervisorAlarmsAreNeverCorrelatedOrCauses`,
  `aLateCauseAdoptsItsAlarms` and `aClearedCauseIsNoCause`.
* [`BoardDeciderTest`] — `aCauseFirstAndACorrelatedFirstGiveTheSameLinks`,
  `aResyncChangeKeepsALinkMadeEarlierInTheSameDecision`, `theFloodStartsAtTheThresholdRaise`,
  `theFloodDoesNotFlapBetweenTheThresholds` and `suppressedRaisesAreNotCounted`.
* [`BoardReducerTest`] (`supervisorStartedEndsTheFlood`) and [`BoardWriterTest`]
  (`aFailedCommitLeavesTheFloodWindowAsItWas`).
* [`EventCodecTest`] (`anAlarmPayloadWithoutCorrelationDecodesAsUncorrelated`) and [`JpaBoardStoreIT`]
  (`openAlarmsLoadWithTheirCorrelation`).

[`AlarmCorrelatorTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmCorrelatorTest.java
[`BoardDeciderTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardDeciderTest.java
[`BoardReducerTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducerTest.java
[`BoardWriterTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriterTest.java
[`EventCodecTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/EventCodecTest.java
[`JpaBoardStoreIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStoreIT.java
