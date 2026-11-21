# ADR-0003: Traps as Low-Latency Hints, Polling as Source of Truth, and Alarm-Table Reconciliation on Every Full Poll

- **Status:** Accepted (implemented)
- **Date:** 2026-11-13
- **Deciders:** Project maintainers

## Context and Problem Statement

SNMP notifications (`TRAP` PDUs on UDP `10162`) are unacknowledged UDP datagrams that can be dropped, delayed, or
reordered during network faults or when an agent is unreachable (`DROP_TRAPS` or `UNREACHABLE`). Even when critical
alarms are sent as acknowledged `INFORM`s, an agent that fails while its management path is broken cannot deliver
notifications at the moment the fault happens.

If a supervision tool relies only on notifications to raise and clear device alarms:

- A lost raise-trap hides an active equipment failure
  ([Hazard H2](../safety/hazard-log.md#h2-lost-traps-hide-device-alarms)).
- A lost clear-trap leaves a cleared alarm open.

## Decision Drivers

- **Fast reaction when notifications arrive:** operators should see a fault within a second under normal network
  conditions.
- **Bounded convergence when notifications are lost:** a dropped trap must never hide an alarm past the next
  successful full poll (5 s default cadence).
- **Fail-closed partial walks:** a table walk that times out or returns malformed rows midway must never be treated as
  an empty alarm table and clear open alarms.

## Considered Options

1. **Apply notifications at once as hints, request an immediate single-flight re-poll, and walk and reconcile
   `atmTsAlarmTable` on every successful full poll (`via RESYNC`), failing closed on incomplete walks** (chosen).
2. **Reconcile `atmTsAlarmTable` only on contact restore, startup, and a 5-minute timer** (leaves a blind spot of up
   to 5 minutes when a single UDP trap is dropped while contact stays up).
3. **Ignore traps and rely only on polling** (adds up to 5 s of delay to every fault).

## Decision Outcome

Chosen option: **Option 1.**

1. **Notifications as hints ([`TrapReceiver`], [`TrapForwarder`], [`BoardDecider`]):**
   - Critical device alarms are sent as acknowledged SNMP `INFORM`s; all other notifications are sent as `TRAP`s to
     UDP `10162`.
   - When a notification arrives, [`BoardDecider.trap`] applies the alarm or status hint, bumps the device's
     `statusGeneration` (`invalidated`), and emits `RepollRequested(id, Reason.TRAP)` so [`PollScheduler`] schedules
     an immediate full poll.
2. **Full alarm-table walk in every full poll ([`PollPlan`], [`ObservationMapper`]):**
   - [`PollPlan`] includes `AtmTsOids.ALARM_ENTRY` in the `GETBULK` walks of every device type (`RADAR`, `SDPS`,
     `FDPS`).
   - [`ObservationMapper`] groups the walked varbinds into [`DeviceAlarm`] records ordered by `(code, instance)`.
   - If the alarm walk throws `SnmpRequestException` midway or any row is missing a required column,
     [`ObservationMapper`] throws `SnmpRequestException("MALFORMED: alarm table ...")` and [`PollScheduler`] submits
     only `PollFailed`. A partial walk never produces a `PollSucceeded` and never clears an alarm.
3. **Single-transaction reconciliation ([`AlarmEngine.reconcile`]):**
   - On every non-stale `PollSucceeded`, [`BoardDecider`] calls [`AlarmEngine.reconcile`] to compare the device's
     active table **D** with the supervisor's open `DEVICE` alarms **S** (`Via.RESYNC`):
     - **In D, not active in S:** raised with `Via.RESYNC` using the device's own `raisedAt` time (a `CLEARED_UNACK`
       alarm with the same key is re-raised under its own alarm ID with the device's `raisedAt`).
     - **Active in S, not in D:** cleared with `Via.RESYNC` (`ACTIVE_ACK` -> `CLOSED`; `ACTIVE_UNACK` ->
       `CLEARED_UNACK`).
     - **In both, D more severe:** updated (`ALARM_UPDATED`) and returned to `ACTIVE_UNACK`.
     - **In both, D less severe:** updated (`ALARM_UPDATED`), keeping its acknowledgement.
     - **In both, same severity, different text:** updated (`ALARM_UPDATED`).
   - Supervisor-derived alarms (`AlarmSource.SUPERVISOR`: `901`, `903`, `904`) are never touched by the device walk.

### Consequences

- **Good:** removes the 5-minute resync blind spot and any per-device resync timer; a lost trap is caught by the next
  successful full poll (5 s cadence).
- **Good:** all reconciliation changes commit in the same PostgreSQL transaction as the poll's equipment and service
  events.
- **Trade-off:** adds one `GETBULK` walk of `atmTsAlarmTable` to every full poll, which is small because a device has
  at most a handful of open alarms.

## Verification

- [`AlarmEngineTest`]: covers each case of the reconciliation table (`raisesATableAlarmMissingFromTheBoard`,
  `clearsAnActiveAlarmMissingFromTheTable`, `aClearedUnacknowledgedAlarmStillInTheTableIsReRaisedWithTheSameId`,
  `escalationGoesBackToUnacknowledged`, `supervisorAlarmsAreNeverReconciled`).
- [`ObservationMapperTest`] and [`PollSchedulerTest`]: `aMissingAlarmWalkFailsTheObservation`,
  `aMalformedAlarmRowFailsTheObservation`, and `aWalkThatFailsMidwaySubmitsOnlyPollFailed`.
- [`SupervisorEndToEndIT`] (`trapLossIsReconciledFromTheAlarmTable`): injects `DROP_TRAPS` and `FAIL` on `RAD-S`, and
  checks that alarm `101` is raised with `via = RESYNC` in `event_log`.

[`AlarmEngine.reconcile`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[`AlarmEngineTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngineTest.java
[`BoardDecider.trap`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
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
