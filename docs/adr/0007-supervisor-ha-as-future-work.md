# ADR-0007: Single Supervisor Instance with State-Preserving Restart; Active/Standby Supervisor HA Left as Future Work

* **Status:** Accepted — Implemented in `v0.1.1`; start-up board from the log after `v0.1.1`
* **Date:** 2026-11-13 (revised 2026-11-15 for `v0.1.1` restart state preservation)
* **Deciders:** Project maintainers

## Context and Problem Statement

While the supervised CNS/ATM estate models redundant equipment pairs (`SDPS-A/B`, `FDPS-A/B`), we needed to decide whether the **ATM Supervisor** process itself should run as a clustered active/standby pair or as a single instance, and how it should behave across restarts ([Hazard H7](../safety/hazard-log.md#h7-a-supervisor-restart-loses-operational-context)).

Running two active supervisor instances concurrently against the same SNMP agents and database tables without distributed leader election would double SNMP poll traffic, interleave conflicting `event_log` transitions, and race on alarm reconciliation.

## Decision Drivers

* **Honest operational context across restarts ([Hazard H7](../safety/hazard-log.md#h7-a-supervisor-restart-loses-operational-context)):** When the supervisor restarts, it must not lose open alarms, must not forget which unit was in maintenance or which unit of a pair was `active`, and must never display pre-restart health as current before polling.
* **Honest downtime representation in history/replay:** Periods when the supervisor was not running must be distinguishable from healthy quiet periods.
* **Keep `v0.1`–`v1.0` focused:** Avoid adding ZooKeeper, Consul, or distributed lock infrastructure that would distract from the core SNMP and domain supervision architecture.

## Considered Options

1. **Run a single supervisor instance backed by PostgreSQL, preserve open alarms and last-known `maintenance`/`role` across restarts while setting health to `UNKNOWN (SUPERVISOR_RESTARTED)`, and record lifecycle/heartbeat events (`SUPERVISOR_STARTED`, `SUPERVISOR_HEARTBEAT`, `SUPERVISOR_STOPPING`)** *(Chosen)*
2. **Active/standby supervisor clustering via a database lease or Raft** (deferred as future work beyond `v1.0`)
3. **Stateless in-memory supervisor that starts from blank inventory defaults on every restart** (rejected because restarting the supervisor would wipe open alarms and reset maintenance flags)

## Decision Outcome

Chosen option: **Option 1.**

### How Restart & Lifecycle Supervision Work in `v0.1.1`

1. **Rebuilding the Board from the Log ([`BoardWriter.start`], [`BoardHistory`]):**
   * On startup, [`BoardWriter.start`] asks [`JpaBoardStore.foldedBoard`] for the board. [`BoardHistory`] builds it from the newest board snapshot plus a fold of every later event, or from [`Board.initial`] over the whole log when no snapshot exists yet. The open alarms come out of that fold, because the `alarm` table is a projection of the same events.
   * The fold keeps each device's last-known `maintenance` flag and `role` (`active`, `standby`, `standalone`), ignores removed device IDs and starts new devices as `UNKNOWN (NOT_YET_POLLED)`. See the [ADR-0004 amendment](0004-full-state-event-log-and-upsert-reducer.md#amendment-the-start-up-board-is-the-fold-of-the-log) and [ADR-0014](0014-replay-from-snapshots-and-the-event-log.md).
2. **Setting Health to `UNKNOWN (SUPERVISOR_RESTARTED)`:**
   * [`BoardWriter.start`] commits and folds `SUPERVISOR_STARTED`, which sets every device's health to `UNKNOWN` with `unknownReason = SUPERVISOR_RESTARTED`. A device keeps its "since" (the restart) and its last contact time, as in any fold of the log.
   * Because `maintenance` and `role` were preserved, a pair with one unit in maintenance immediately evaluates its service as `UNKNOWN · at best DEGRADED` rather than falsely claiming `at best OK` before the first poll.
   * Each device's first successful full poll then confirms live health and reconciles its alarm table (`via RESYNC`).
3. **Lifecycle Heartbeats for Replay Down-Gap Detection:**
   * [`BoardWriter`] commits a `SUPERVISOR_HEARTBEAT` event every 60 seconds and a `SUPERVISOR_STOPPING` event on graceful shutdown.
   * When historical replay reconstructs the board for timestamp `T` and finds no event other than a telemetry sample in the 90 s before `T` (1.5 × the heartbeat interval), or finds that the newest one is `SUPERVISOR_STOPPING`, it marks the board `NO_DATA` (*"No recorded data: supervisor not running"*).

### Consequences

* **Good:** Restarting the supervisor preserves open alarms, maintenance flags, and active/standby roles, while honestly marking health as `UNKNOWN (SUPERVISOR_RESTARTED)` until the first poll completes.
* **Good:** Because all durable state already lives in PostgreSQL and `Board` is a pure fold of `event_log`, adding active/standby supervisor failover in the future only requires a lease table gating [`PollScheduler`] and [`BoardWriter`].
* **Bad / Trade-off:** While the single supervisor container is stopped, nothing is supervised.

## Verification

* [`BoardWriterTest`] — Verifies `restartLoadsOpenAlarmsAndMarksSupervisorRestarted`, `restartKeepsLastKnownMaintenanceAndRole`, and `heartbeatTicksAreCommitted`.
* [`ReplayFidelityIT`] — Verifies `aRestartRebuildsTheSameBoardFromTheLog` and `aStartWithoutSnapshotsFoldsTheWholeLog`.

[BoardWriter]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[BoardWriter.start]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[Board.initial]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/Board.java
[PollScheduler]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[JpaBoardStore]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[EventLogRepository]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/EventLogRepository.java
[BoardWriterTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriterTest.java
[ReplayFidelityIT]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/ReplayFidelityIT.java
[`Board.initial`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/Board.java
[`BoardWriter.start`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`JpaBoardStore.foldedBoard`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[`BoardHistory`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/BoardHistory.java
[`BoardWriterTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriterTest.java
[`BoardWriter`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`EventLogRepository`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/EventLogRepository.java
[`JpaBoardStore`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[`PollScheduler`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[`ReplayFidelityIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/ReplayFidelityIT.java
