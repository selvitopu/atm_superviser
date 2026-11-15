# ADR-0007: Single Supervisor Instance with State-Preserving Restart; Active/Standby Supervisor HA Left as Future Work

* **Status:** Accepted — Implemented in `v0.1.1`
* **Date:** 2026-11-13 (revised 2026-11-15 for `v0.1.1` restart state preservation)
* **Deciders:** Project maintainers

## Context and Problem Statement

While the supervised CNS/ATM estate models redundant equipment pairs (`SDPS-A/B`, `FDPS-A/B`), we needed to decide whether the **ATM Supervisor** process itself should run as a clustered active/standby pair or as a single instance, and how it should behave across restarts ([Hazard H7](../safety/hazard-log.md#h7-a-supervisor-restart-loses-operational-context)).

Running two active supervisor instances concurrently against the same SNMP agents and MySQL tables without distributed leader election would double SNMP poll traffic, interleave conflicting `event_log` transitions, and race on alarm reconciliation.

## Decision Drivers

* **Honest operational context across restarts ([Hazard H7](../safety/hazard-log.md#h7-a-supervisor-restart-loses-operational-context)):** When the supervisor restarts, it must not lose open alarms, must not forget which unit was in maintenance or which unit of a pair was `active`, and must never display pre-restart health as current before polling.
* **Honest downtime representation in history/replay:** Periods when the supervisor was not running must be distinguishable from healthy quiet periods.
* **Keep `v0.1`–`v1.0` focused:** Avoid adding ZooKeeper, Consul, or distributed lock infrastructure that would distract from the core SNMP and domain supervision architecture.

## Considered Options

1. **Run a single supervisor instance backed by MySQL, preserve open alarms and last-known `maintenance`/`role` across restarts while setting health to `UNKNOWN (SUPERVISOR_RESTARTED)`, and record lifecycle/heartbeat events (`SUPERVISOR_STARTED`, `SUPERVISOR_HEARTBEAT`, `SUPERVISOR_STOPPING`)** *(Chosen)*
2. **Active/standby supervisor clustering via a database lease or Raft** (deferred as future work beyond `v1.0`)
3. **Stateless in-memory supervisor that starts from blank inventory defaults on every restart** (rejected because restarting the supervisor would wipe open alarms and reset maintenance flags)

## Decision Outcome

Chosen option: **Option 1.**

### How Restart & Lifecycle Supervision Work in `v0.1.1`

1. **Reloading Open Alarms & Last-Known Equipment Context ([`BoardWriter.start`], [`JpaBoardStore`]):**
   * On startup, [`BoardWriter.start`] queries [`JpaBoardStore.loadOpenAlarms`] to restore all non-closed alarms (`ACTIVE_UNACK`, `ACTIVE_ACK`, `CLEARED_UNACK`).
   * On any non-first start (when `event_log` is non-empty), [`JpaBoardStore.loadLastEquipmentStates`] queries the newest `EQUIPMENT_STATE_CHANGED`, `CONTACT_LOST`, or `CONTACT_RESTORED` event per device ([`EventLogRepository`]) and passes those states to [`Board.initial`].
   * [`Board.initial`] preserves each device's last-known `maintenance` flag and `role` (`active`, `standby`, `standalone`) while ignoring any removed device IDs and defaulting new devices.
2. **Setting Health to `UNKNOWN (SUPERVISOR_RESTARTED)`:**
   * [`BoardWriter.start`] commits and folds `SUPERVISOR_STARTED`, which sets every device's health to `UNKNOWN` with `unknownReason = SUPERVISOR_RESTARTED`.
   * Because `maintenance` and `role` were preserved, a pair with one unit in maintenance immediately evaluates its service as `UNKNOWN · at best DEGRADED` rather than falsely claiming `at best OK` before the first poll.
   * Each device's first successful full poll then confirms live health and reconciles its alarm table (`via RESYNC`).
3. **Lifecycle Heartbeats for Replay Down-Gap Detection:**
   * [`BoardWriter`] commits a `SUPERVISOR_HEARTBEAT` event every 60 seconds and a `SUPERVISOR_STOPPING` event on graceful shutdown.
   * When historical replay (`v0.3`) reconstructs the board for timestamp `T` and finds no event or heartbeat in the 90 s before `T`, it marks the board `NO_DATA` (*"No recorded data: supervisor not running"*).

### Consequences

* **Good:** Restarting the supervisor preserves open alarms, maintenance flags, and active/standby roles, while honestly marking health as `UNKNOWN (SUPERVISOR_RESTARTED)` until the first poll completes.
* **Good:** Because all durable state already lives in MySQL and `Board` is a pure fold of `event_log`, adding active/standby supervisor failover in the future only requires a lease table gating [`PollScheduler`] and [`BoardWriter`].
* **Bad / Trade-off:** While the single supervisor container is stopped, nothing is supervised.

## Verification

* [`BoardWriterTest`] — Verifies `restartLoadsOpenAlarmsAndMarksSupervisorRestarted`, `restartKeepsLastKnownMaintenanceAndRole`, `restartIgnoresStoredStateForUnknownIdsAndDefaultsTheRest`, and `heartbeatTicksAreCommitted`.
* [`JpaBoardStoreIT`] — Verifies `lastEquipmentStatesAreTheNewestEquipmentEventPerDevice`.

[BoardWriter]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[BoardWriter.start]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[Board.initial]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/Board.java
[PollScheduler]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[JpaBoardStore]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[JpaBoardStore.loadOpenAlarms]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[JpaBoardStore.loadLastEquipmentStates]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[EventLogRepository]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/EventLogRepository.java
[BoardWriterTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriterTest.java
[JpaBoardStoreIT]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStoreIT.java
[`Board.initial`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/Board.java
[`BoardWriter.start`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`BoardWriterTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriterTest.java
[`BoardWriter`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`EventLogRepository`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/EventLogRepository.java
[`JpaBoardStore.loadLastEquipmentStates`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[`JpaBoardStore.loadOpenAlarms`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[`JpaBoardStoreIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStoreIT.java
[`JpaBoardStore`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[`PollScheduler`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
