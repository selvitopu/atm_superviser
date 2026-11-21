# ADR-0004: Full-State Event Log, Pure Upsert Reducer, and 30-Second Sampled Telemetry

- **Status:** Accepted (implemented; see also [ADR-0014](0014-replay-from-snapshots-and-the-event-log.md))
- **Date:** 2026-11-13
- **Deciders:** Project maintainers

## Context and Problem Statement

ATM Supervisor keeps an audit log of every operational change (`event_log`) that serves two purposes:

1. Updating the live in-memory [`Board`] after each database commit (`decide -> commit -> apply -> publish`).
2. Rebuilding the board operators saw at any past instant `T` during incident replay
   ([ADR-0014](0014-replay-from-snapshots-and-the-event-log.md)).

At the same time, writing a durable event row on every 5-second poll whenever a plot counter or latency value changes
would fill `event_log` with noise and bury real state transitions.

## Decision Drivers

- **Replay must show what operators saw at the time:** re-running today's service rules or alarm logic during replay
  could give a different answer if the rules change later.
- **Browser and server state must not drift:** the Java board fold and the Angular store's delta handler should apply
  events the same way without duplicating domain rules in TypeScript.
- **Bounded telemetry storage without fake precision:** operational state changes must be recorded in full, while
  numerical values should be sampled at a fixed 30 s interval with no deadband and no fabricated data.

## Considered Options

1. **Store the full new entity state in every `event_log.payload`, fold events with a pure entity-upsert
   [`BoardReducer`], stream poll-rate telemetry over SSE without storing it, and persist one `TELEMETRY_SAMPLED` event
   per device every 30 s while polls succeed** (chosen).
2. **Store field-level diffs (`oldValue -> newValue`) and recompute derived services during replay.**
3. **Store every 5 s poll observation in `event_log`.**

## Decision Outcome

Chosen option: **Option 1.**

1. **Events are self-contained facts ([`DomainEvent`], [`EventCodec`]):**
   - Each event committed to `event_log` (`EQUIPMENT_STATE_CHANGED`, `CONTACT_LOST`, `CONTACT_RESTORED`,
     `SERVICE_STATE_CHANGED`, `ALARM_RAISED`, `ALARM_UPDATED`, `ALARM_ACKNOWLEDGED`, `ALARM_CLEARED`, `ALARM_CLOSED`,
     `ALARM_FLOOD_STARTED`, `ALARM_FLOOD_ENDED`, `EQUIPMENT_COMMANDED`, `SUPERVISOR_STARTED`, `SUPERVISOR_HEARTBEAT`,
     `SUPERVISOR_STOPPING`) carries the full new state of the entity it changed, tagged with `schemaVersion`.
2. **Pure upsert [`BoardReducer`]:**
   - [`BoardReducer.apply`] folds a [`DomainEvent`] into an immutable [`Board`] by upserting the entity by ID (or
     removing an alarm on `ALARM_CLOSED`).
   - It never runs [`ServiceEvaluator`] or [`AlarmEngine`], which run only in [`BoardDecider`] (step 1 of
     [`BoardWriter`]).
   - In [`BoardWriter`], the in-memory board is updated only by folding the events that just committed in PostgreSQL
     ([`BoardReducer.apply`] for each committed event). If a commit fails, the board is untouched and the next input
     retries ([`BoardWriterTest`] `commitFailureLeavesBoardUnchangedAndRetries`).
3. **Volatile live telemetry vs. 30 s `TELEMETRY_SAMPLED`:**
   - Every successful full poll sends a volatile `telemetry` SSE frame (with no SSE `id`) for live values and
     freshness ages.
   - [`BoardDecider`] emits one durable `TELEMETRY_SAMPLED` event per device every 30 seconds while full polls
     succeed, with no deadband so stable values are sampled too. Missed polls emit no sample.

### Consequences

- **Good:** the live board is by construction the fold of `event_log` in `id` order.
- **Good:** on supervisor restart, the start-up board is the fold of the log (below), so each device keeps its
  last-known `maintenance` flag and `role` while its health becomes `UNKNOWN (SUPERVISOR_RESTARTED)`.
- **Trade-off:** replayed values have at most 30 s granularity rather than 5 s poll granularity; the Replay screen
  labels every tile with its `sampled HH:MM:SSZ` time and never interpolates.

### Amendment: The Start-Up Board Is the Fold of the Log

Since replay was added ([ADR-0014](0014-replay-from-snapshots-and-the-event-log.md)), [`BoardWriter`] no longer builds
its start-up board from [`Board.initial`] with the open alarm rows and each device's newest equipment event. It starts
from [`BoardStore.foldedBoard`], which [`BoardHistory`] computes as the newest board snapshot plus a fold of every
later event, or as a fold of the whole log when no snapshot exists yet (the first start after an upgrade); replay
rebuilds the board at any instant with the same fold. `SUPERVISOR_STARTED` then marks every device
`UNKNOWN (SUPERVISOR_RESTARTED)` as before, and a restarted device keeps its "since" (the restart, or the earlier time
it became `UNKNOWN`) and its last contact time. The live board therefore stays the fold of `event_log` across
restarts, and its open alarms come from the log, of which the `alarm` table is a projection.

## Verification

- [`BoardReducerTest`] and [`EventCodecTest`]: pure entity upsert folding and JSON round-trip serialisation of every
  [`DomainEvent`] variant.
- [`BoardDeciderTest`]: 30 s `TELEMETRY_SAMPLED` emission (including stable values without a deadband) and no sample
  on failed polls.
- [`BoardWriterTest`] and [`JpaBoardStoreIT`]: single-transaction commit, retry without losing pending telemetry
  samples (`commitFailureDoesNotLoseTheTelemetrySample`), and a start-up board taken from the folded log
  (`startUsesTheFoldedBoard`).
- [`ReplayFidelityIT`]: the live board equals the board rebuilt for now and the fold of the whole log, and a restarted
  writer rebuilds the same board from the log (`theLiveBoardEqualsBoardAtNowAndTheFoldFromTheStart`,
  `aRestartRebuildsTheSameBoardFromTheLog`, `aStartWithoutSnapshotsFoldsTheWholeLog`).

[`AlarmEngine`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[`Board.initial`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/Board.java
[`BoardDeciderTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardDeciderTest.java
[`BoardDecider`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[`BoardHistory`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/BoardHistory.java
[`BoardReducer.apply`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducer.java
[`BoardReducerTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducerTest.java
[`BoardReducer`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducer.java
[`BoardStore.foldedBoard`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardStore.java
[`BoardWriterTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriterTest.java
[`BoardWriter`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`Board`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/Board.java
[`DomainEvent`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/DomainEvent.java
[`EventCodecTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/EventCodecTest.java
[`EventCodec`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/EventCodec.java
[`JpaBoardStoreIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStoreIT.java
[`ReplayFidelityIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/ReplayFidelityIT.java
[`ServiceEvaluator`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluator.java
