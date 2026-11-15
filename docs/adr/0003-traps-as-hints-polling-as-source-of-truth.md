# ADR-0003: Traps as Low-Latency Hints, Polling as Source of Truth, and Alarm-Table Reconciliation on Every Full Poll

* **Status:** Accepted — Implemented in `v0.1.1`
* **Date:** 2026-11-13 (revised 2026-11-15 for `v0.1.1` full-poll alarm resync)
* **Deciders:** Project maintainers

## Context and Problem Statement

SNMP notifications (`TRAP` PDUs on UDP `10162`) are unacknowledged UDP datagrams that can be dropped, delayed, or reordered during network disturbances or when an agent is temporarily unreachable (`DROP_TRAPS` / `UNREACHABLE`). Even when critical alarms are sent as acknowledged `INFORM`s, an agent that fails while its management path is broken cannot deliver notifications at the moment the fault occurs.

If a supervision system relies solely on notifications to raise and clear device alarms:
* A lost raise-trap permanently hides an active equipment failure ([Hazard H2](../safety/hazard-log.md#h2-lost-traps-hide-device-alarms)).
* A lost clear-trap leaves a cleared alarm stuck open forever.

## Decision Drivers

* **Sub-second responsiveness when notifications arrive:** Operators should see faults within a second under normal network conditions.
* **Self-healing convergence when notifications are lost:** A dropped notification must never hide an alarm beyond the next successful full poll (5 s default cadence).
* **Fail-closed partial walk safety:** A table walk that times out or returns malformed rows midway must never be treated as an empty alarm table and falsely clear open alarms.

## Considered Options

1. **Apply notifications immediately as hints + trigger an immediate single-flight re-poll + walk and reconcile `atmTsAlarmTable` on every successful full poll (`via RESYNC`), failing closed on incomplete walks** *(Chosen in `v0.1.1`)*
2. **Reconcile `atmTsAlarmTable` only on contact restore, startup, and a 5-minute timer** (originally considered in the early design, but leaves a blind spot of up to 5 minutes if a single UDP trap is dropped during normal reachability)
3. **Ignore traps completely and rely only on polling** (adds up to 5 s latency to every fault)

## Decision Outcome

Chosen option: **Option 1.**

### How It Works in `v0.1.1`

1. **Notifications as Hints ([`TrapReceiver`], [`TrapForwarder`], [`BoardDecider`]):**
   * Critical device alarms are sent as acknowledged SNMP `INFORM`s; all other notifications are sent as `TRAP`s to UDP `10162`.
   * When a notification arrives, [`BoardDecider.trap`] applies the immediate alarm/status hint, increments the device's `statusGeneration` (`invalidated`), and emits `RepollRequested(id, Reason.TRAP)` so [`PollScheduler`] schedules an immediate full poll.
2. **Full Alarm-Table Walk in Every Full Poll ([`PollPlan`], [`ObservationMapper`]):**
   * [`PollPlan`] includes `AtmTsOids.ALARM_ENTRY` in the `GETBULK` walks of every device type (`RADAR`, `SDPS`, `FDPS`).
   * [`ObservationMapper`] groups walked varbinds into [`DeviceAlarm`] records ordered by `(code, instance)`.
   * **Fail-closed partial walk:** If the alarm walk throws `SnmpRequestException` midway or any row is missing a required column, [`ObservationMapper`] throws `SnmpRequestException("MALFORMED: alarm table ...")` and [`PollScheduler`] submits only `PollFailed`. A partial walk never produces a `PollSucceeded` and never clears an alarm.
3. **Single-Transaction Reconciliation ([`AlarmEngine.reconcile`]):**
   * On every non-stale `PollSucceeded`, [`BoardDecider`] calls [`AlarmEngine.reconcile`] to compare the device's active table **D** against the supervisor's open `DEVICE`-sourced alarms **S** (`Via.RESYNC`):
     * **In D, not active in S:** Raised with `Via.RESYNC` using the device's own `raisedAt` timestamp (a `CLEARED_UNACK` alarm with the same key is re-raised keeping its alarm ID and updating `raisedAt` to the device's timestamp).
     * **Active in S, not in D:** Cleared with `Via.RESYNC` (`ACTIVE_ACK` $\rightarrow$ `CLOSED`; `ACTIVE_UNACK` $\rightarrow$ `CLEARED_UNACK`).
     * **In both, D more severe:** Updated (`ALARM_UPDATED`) and returned to `ACTIVE_UNACK`.
     * **In both, D less severe:** Updated (`ALARM_UPDATED`), keeping acknowledgement.
     * **In both, same severity, different text:** Updated (`ALARM_UPDATED`).
   * Supervisor-derived alarms (`AlarmSource.SUPERVISOR`: `901`, `903`, `904`) are never touched by the device walk.

### Consequences

* **Good:** Eliminates the 5-minute resync blind spot and removes per-device "resync due" timer state; a lost trap is corrected by the next successful full poll (5 s cadence).
* **Good:** All reconciliation changes commit in the same PostgreSQL transaction as the poll's equipment and service events.
* **Bad / Trade-off:** Adds one `GETBULK` walk of `atmTsAlarmTable` to every full poll (negligible overhead because a device has at most a handful of active alarms).

## Verification

* [`AlarmEngineTest`] — Unit tests for every row of the reconciliation table (`raisesATableAlarmMissingFromTheBoard`, `clearsAnActiveAlarmMissingFromTheTable`, `aClearedUnacknowledgedAlarmStillInTheTableIsReRaisedWithTheSameId`, `escalationGoesBackToUnacknowledged`, `supervisorAlarmsAreNeverReconciled`).
* [`ObservationMapperTest`] & [`PollSchedulerTest`] — Verify `aMissingAlarmWalkFailsTheObservation`, `aMalformedAlarmRowFailsTheObservation`, and `aWalkThatFailsMidwaySubmitsOnlyPollFailed`.
* [`SupervisorEndToEndIT`] (`trapLossIsReconciledFromTheAlarmTable`) — Injects `DROP_TRAPS` and `FAIL` on `RAD-S`, and verifies that alarm `101` is raised with `via = RESYNC` in `event_log`.

[TrapReceiver]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/TrapReceiver.java
[TrapForwarder]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/TrapForwarder.java
[BoardDecider]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[PollScheduler]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[PollPlan]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/PollPlan.java
[ObservationMapper]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/ObservationMapper.java
[DeviceAlarm]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/DeviceAlarm.java
[AlarmEngine.reconcile]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[AlarmEngineTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngineTest.java
[ObservationMapperTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/snmp/ObservationMapperTest.java
[PollSchedulerTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/polling/PollSchedulerTest.java
[SupervisorEndToEndIT]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorEndToEndIT.java
[`BoardDecider.trap`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[`AlarmEngine.reconcile`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[`AlarmEngineTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngineTest.java
[`BoardDecider`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[`DeviceAlarm`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/DeviceAlarm.java
[`ObservationMapperTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/snmp/ObservationMapperTest.java
[`ObservationMapper`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/ObservationMapper.java
[`PollPlan`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/PollPlan.java
[`PollSchedulerTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/polling/PollSchedulerTest.java
[`PollScheduler`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[`SupervisorEndToEndIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorEndToEndIT.java
[`TrapForwarder`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/TrapForwarder.java
[`TrapReceiver`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/TrapReceiver.java
