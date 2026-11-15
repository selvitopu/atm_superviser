# ADR-0004: Full-State Event Log, Pure Upsert Reducer, and 30-Second Sampled Telemetry

* **Status:** Accepted — Event log, pure `BoardReducer`, and 30 s `TELEMETRY_SAMPLED` implemented in `v0.1.1`; snapshots, replay queries, and export UI planned for `v0.3`
* **Date:** 2026-11-13 (revised 2026-11-15 for `v0.1.1`)
* **Deciders:** Project maintainers

## Context and Problem Statement

ATM Supervisor needs an audit log of every operational change (`event_log`) that serves two purposes:
1. Updating the live in-memory [`Board`] deterministically after each database commit (`derive → commit → apply → publish`).
2. Reconstructing the exact board state operators saw at any historical timestamp `T` during incident replay (`v0.3`).

At the same time, storing a durable event row on every 5-second poll just because a noisy radar plot counter or latency gauge changed would flood `event_log` and obscure genuine operational state transitions.

## Decision Drivers

* **Replay must show what operators actually saw at the time:** Re-running today's service rules or alarm logic during historical replay could produce a different picture if rules evolve.
* **Browser and server state must never drift:** The Java board fold and the Angular store delta handler should apply events identically without duplicating domain rules in TypeScript.
* **Bounded telemetry storage without fake precision ([Amendment §5](../refactor/ATM-Supervisor-Architecture-Review-Decisions.md)):** Durable operational events must be 100% faithful, while numerical telemetry should be sampled at a fixed 30 s interval with no deadband and no fabricated values.

## Considered Options

1. **Store the complete new entity state in every `event_log.payload`, fold events with a pure entity-upsert [`BoardReducer`], stream poll-rate telemetry over SSE without storing it, and persist one `TELEMETRY_SAMPLED` event per device every 30 s while polls succeed** *(Chosen)*
2. **Store field-level diffs (`oldValue → newValue`) and recompute derived services during replay**
3. **Store every 5 s poll observation in `event_log`**

## Decision Outcome

Chosen option: **Option 1.**

### Key Design Elements Implemented in `v0.1.1`

1. **Events Are Self-Contained Facts ([`DomainEvent`], [`EventCodec`]):**
   * Each event committed to `event_log` (`EQUIPMENT_STATE_CHANGED`, `CONTACT_LOST`, `CONTACT_RESTORED`, `SERVICE_STATE_CHANGED`, `ALARM_RAISED`, `ALARM_UPDATED`, `ALARM_ACKNOWLEDGED`, `ALARM_CLEARED`, `ALARM_CLOSED`, `SUPERVISOR_STARTED`, `SUPERVISOR_HEARTBEAT`, `SUPERVISOR_STOPPING`) carries the **full new state** of the entity it changed (`EquipmentState`, `ServiceState`, or `Alarm`), tagged with `schemaVersion`.
2. **Pure Upsert [`BoardReducer`]:**
   * [`BoardReducer.apply`] folds a [`DomainEvent`] into an immutable [`Board`] by upserting the entity by ID (or removing an alarm when `ALARM_CLOSED`).
   * It never runs [`ServiceEvaluator`] or [`AlarmEngine`], which run only in [`BoardDecider`] (Step 1 of [`BoardWriter`]).
   * In [`BoardWriter`], the in-memory board is updated **only** by folding the events that just committed in PostgreSQL ([`BoardReducer.apply`] for each committed event). If a commit fails, the board remains unchanged and the next input retries ([`BoardWriterTest`] `commitFailureLeavesBoardUnchangedAndRetries`).
3. **Volatile Live Telemetry vs. 30 s `TELEMETRY_SAMPLED`:**
   * Every successful full poll emits a volatile `telemetry` SSE frame (with no SSE `id`) for live UI gauges and freshness ages.
   * [`BoardDecider`] emits one durable `TELEMETRY_SAMPLED` event per device every **30 seconds** while full polls succeed, with **no deadband** so stable values are sampled too. Missed polls emit no sample.

### Consequences

* **Good:** The live board is by construction the fold of `event_log` in `id` order.
* **Good:** On supervisor restart (`v0.1.1`), [`JpaBoardStore.loadLastEquipmentStates`] decodes the newest equipment event per device so [`Board.initial`] preserves each device's last-known `maintenance` flag and `role` while setting health to `UNKNOWN (SUPERVISOR_RESTARTED)`.
* **Bad / Trade-off:** Replay values (`v0.3`) have $\le 30\text{ s}$ granularity rather than 5 s poll granularity; the replay UI explicitly labels sample timestamps and ages rather than interpolating.

## Verification

* [`BoardReducerTest`] & [`EventCodecTest`] — Verify pure entity upsert folding and JSON round-trip serialisation of every [`DomainEvent`] variant.
* [`BoardDeciderTest`] — Verifies 30 s `TELEMETRY_SAMPLED` emission (including stable values without deadband) and no sample on failed polls.
* [`BoardWriterTest`] & [`JpaBoardStoreIT`] — Verify single-transaction commit, retry without losing pending telemetry samples (`commitFailureDoesNotLoseTheTelemetrySample`), and `lastEquipmentStatesAreTheNewestEquipmentEventPerDevice`.

[Board]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/Board.java
[Board.initial]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/Board.java
[DomainEvent]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/DomainEvent.java
[BoardReducer]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducer.java
[BoardReducer.apply]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducer.java
[BoardDecider]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[BoardWriter]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[ServiceEvaluator]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluator.java
[AlarmEngine]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[EventCodec]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/EventCodec.java
[JpaBoardStore.loadLastEquipmentStates]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[BoardReducerTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducerTest.java
[EventCodecTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/EventCodecTest.java
[BoardDeciderTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardDeciderTest.java
[BoardWriterTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriterTest.java
[JpaBoardStoreIT]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStoreIT.java
[`AlarmEngine`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[`Board.initial`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/Board.java
[`BoardDeciderTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardDeciderTest.java
[`BoardDecider`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[`BoardReducer.apply`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducer.java
[`BoardReducerTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducerTest.java
[`BoardReducer`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducer.java
[`BoardWriterTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriterTest.java
[`BoardWriter`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`Board`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/Board.java
[`DomainEvent`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/DomainEvent.java
[`EventCodecTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/EventCodecTest.java
[`EventCodec`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/EventCodec.java
[`JpaBoardStore.loadLastEquipmentStates`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[`JpaBoardStoreIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStoreIT.java
[`ServiceEvaluator`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluator.java
