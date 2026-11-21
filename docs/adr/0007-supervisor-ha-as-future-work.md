# ADR-0007: Single Supervisor Instance with State-Preserving Restart; Active/Standby Supervisor HA Left as Future Work

- **Status:** Accepted (implemented)
- **Date:** 2026-11-13
- **Deciders:** Project maintainers

## Context and Problem Statement

While the supervised estate models redundant equipment pairs (`SDPS-A/B`, `FDPS-A/B`), we needed to decide whether the
ATM Supervisor process itself should run as an active/standby cluster or as a single instance, and how it should
behave across restarts
([Hazard H7](../safety/hazard-log.md#h7-a-supervisor-restart-loses-operational-context)).

Running two active supervisor instances at once against the same SNMP agents and database tables without leader
election would double SNMP polling traffic, interleave conflicting `event_log` transitions, and race on alarm
reconciliation.

## Decision Drivers

- **Keep operational context across restarts
  ([Hazard H7](../safety/hazard-log.md#h7-a-supervisor-restart-loses-operational-context)):** when the supervisor
  restarts, it must keep open alarms, remember which unit was in maintenance and which unit of a pair was `active`,
  and never show pre-restart health as current before polling.
- **Honest downtime in history and replay:** periods when the supervisor was not running must be distinguishable from
  healthy quiet periods.
- **Keep the stack small:** avoid adding ZooKeeper, Consul, or external lock services.

## Considered Options

1. **Run a single supervisor instance backed by PostgreSQL, rebuild the start-up board from the stored event log while
   setting health to `UNKNOWN (SUPERVISOR_RESTARTED)`, and record lifecycle and heartbeat events (`SUPERVISOR_STARTED`,
   `SUPERVISOR_HEARTBEAT`, `SUPERVISOR_STOPPING`)** (chosen).
2. **Active/standby supervisor clustering through a database lease or Raft** (left as future work beyond `v1.0`).
3. **Stateless in-memory supervisor that starts from blank inventory defaults on every restart** (rejected because a
   restart would wipe open alarms and reset maintenance flags).

## Decision Outcome

Chosen option: **Option 1.**

1. **Rebuilding the board from the log ([`BoardWriter.start`], [`BoardHistory`]):**
   - On startup, [`BoardWriter.start`] asks [`JpaBoardStore.foldedBoard`] for the board. [`BoardHistory`] builds it
     from the newest board snapshot plus a fold of every later event, or from [`Board.initial`] over the whole log
     when no snapshot exists yet. The open alarms come out of that fold, because the `alarm` table is a projection of
     the same events.
   - The fold keeps each device's last-known `maintenance` flag and `role` (`active`, `standby`, `standalone`), drops
     removed device IDs, and starts new devices as `UNKNOWN (NOT_YET_POLLED)`. See
     [ADR-0004](0004-full-state-event-log-and-upsert-reducer.md#amendment-the-start-up-board-is-the-fold-of-the-log)
     and [ADR-0014](0014-replay-from-snapshots-and-the-event-log.md).
2. **Setting health to `UNKNOWN (SUPERVISOR_RESTARTED)`:**
   - [`BoardWriter.start`] commits and folds `SUPERVISOR_STARTED`, which sets every device's health to `UNKNOWN` with
     `unknownReason = SUPERVISOR_RESTARTED`. A device keeps its "since" (the restart) and its last contact time, as in
     any fold of the log.
   - Because `maintenance` and `role` are preserved, a pair with one unit in maintenance evaluates its service as
     `UNKNOWN · at best DEGRADED` rather than `at best OK` before the first poll.
   - Each device's first successful full poll then sets live health and reconciles its alarm table (`via RESYNC`).
3. **Lifecycle heartbeats for replay gap detection:**
   - [`BoardWriter`] commits a `SUPERVISOR_HEARTBEAT` event every 60 seconds and a `SUPERVISOR_STOPPING` event on
     graceful shutdown.
   - When replay rebuilds the board for instant `T` and finds no event other than a telemetry sample in the 90 s
     before `T` (1.5 times the heartbeat interval), or finds that the newest one is `SUPERVISOR_STOPPING`, it marks
     the board `NO_DATA` (*"No recorded data: supervisor not running"*).

### Consequences

- **Good:** restarting the supervisor keeps open alarms, maintenance flags, and active/standby roles, while marking
  health as `UNKNOWN (SUPERVISOR_RESTARTED)` until the first poll finishes.
- **Good:** because durable state lives in PostgreSQL and `Board` is a pure fold of `event_log`, adding active/standby
  supervisor failover later only needs a lease table gating [`PollScheduler`] and [`BoardWriter`].
- **Trade-off:** while the single supervisor container is stopped, nothing is supervised.

## Verification

- [`BoardWriterTest`]: `restartLoadsOpenAlarmsAndMarksSupervisorRestarted`, `restartKeepsLastKnownMaintenanceAndRole`,
  and `heartbeatTicksAreCommitted`.
- [`ReplayFidelityIT`]: `aRestartRebuildsTheSameBoardFromTheLog` and `aStartWithoutSnapshotsFoldsTheWholeLog`.

[`Board.initial`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/Board.java
[`BoardHistory`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/BoardHistory.java
[`BoardWriter.start`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`BoardWriterTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriterTest.java
[`BoardWriter`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`JpaBoardStore.foldedBoard`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[`PollScheduler`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[`ReplayFidelityIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/ReplayFidelityIT.java
