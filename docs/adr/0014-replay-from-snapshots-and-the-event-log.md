# ADR-0014: Replay Rebuilds the Board from Snapshots and the Event Log; Playback Runs in the Browser

* **Status:** Accepted — Implemented after `v0.1.1`
* **Date:** 2026-11-21
* **Deciders:** Project maintainers

## Context and Problem Statement

After an incident the System Controller asks two questions: what exactly did the board show at 14:02, and what happened next? The event log already stores full-state facts in order, and a pure reducer folds them into the board ([ADR-0004](0004-full-state-event-log-and-upsert-reducer.md)), so the past can be rebuilt. Three things were still missing: a cheap way to get the board at an arbitrary instant, a screen that plays it forward without ever being mistaken for the live board ([Hazard H17](../safety/hazard-log.md#h17-replay-mistaken-for-live)), and an honest answer for the periods when the supervisor was not running ([ADR-0007](0007-supervisor-ha-as-future-work.md)).

## Decision Drivers

* **A replayed board must equal what live showed at that instant.** The same reducer and the same events, never a second rule set.
* **Replay must never be mistaken for live.** The separation has to be structural, not a matter of care.
* **A gap is not a green board.** A period with nothing supervised is shown as "No recorded data", never as quiet health.
* **Seeking must stay cheap** however long the log grows, and the server must keep no state per viewer.

## Considered Options

1. **The board at an instant on the server, playback in the browser** *(Chosen)*. The server folds the nearest snapshot plus the later events. The browser then fetches the following events in pages and applies them on a timer with the same upsert the live board uses.
2. **Fold the whole log in the browser.** Rejected: the cost grows with the log, and it would duplicate the reducer in TypeScript.
3. **Server-side replay sessions that stream events over SSE at speed.** Rejected: it needs a clock and state per viewer on the server, seeking means tearing a stream down, and a second stream next to the live one is the easiest way to leak live data into replay, or the reverse.

## Decision Outcome

Chosen option: **Option 1.**

### Snapshots

The `board_snapshot` table (changeset `005`) holds the durable board (equipment, services, open alarms, flood state and the id of the last event folded in) as versioned JSON. It never holds telemetry, generations or the raise window, which are live-only. [`BoardWriter`] takes one at start-up and then on the first commit or heartbeat at least `atm.history.snapshot-interval` (default `5m`) after the previous attempt. It takes it from its own live board right after applying a commit, and that board is the fold of the log, so a snapshot always equals the fold of every event up to its last event id. A snapshot is written outside the commit's transaction and is best effort: a failure is logged, counted in `atm_snapshot_failures_total` and never touches the live board. Nothing is purged in this step.

### The board at an instant

[`BoardHistory`] takes the newest snapshot at or before the instant (or `Board.initial` when there is none), folds the later events up to the instant with [`BoardReducer`], and adds each unit's newest telemetry sample. A sample counts only if it is newer than the newest `SUPERVISOR_STARTED` at or before the instant, because live drops telemetry at a restart. An instant in the future is answered as now. Folding from a snapshot equals folding from the start of the log, and the live board equals the board at now; both are tested.

### The gap rule

The board has no data at T when no event other than `TELEMETRY_SAMPLED` has `occurred_at` in `(T − threshold, T]`, or when the newest such event at or before T is `SUPERVISOR_STOPPING`, or when T is before the first event. The threshold is 1.5 × `atm.supervisor.heartbeat-interval`, so 90 s at the default 60 s. The server (a seek, the timeline and the incident summary) applies this one rule in [`GapRule`]. During play the browser applies it with the threshold the server sends in the board response, and after a seek it also receives the time and type of the newest recorded event, so the gap starts exactly one threshold after the last recorded event.

### The start-up board is the fold of the log

Before replay, [`BoardWriter.start`] seeded the live board from the open alarm rows and each device's newest equipment event, which left a restarted unit with no "since" and no last contact, while a fold of the log keeps both. That broke "live equals fold" at every restart. The start-up board is now the fold of the log, taken from the newest snapshot (or the whole log when there is none, as on the first start after an upgrade), and `SUPERVISOR_STARTED` is committed on top as before. After a restart a unit shows `UNKNOWN (SUPERVISOR_RESTARTED) since <restart>` and keeps its last contact. The two loaders this replaced are removed. See the amendment in [ADR-0004](0004-full-state-event-log-and-upsert-reducer.md#amendment-the-start-up-board-is-the-fold-of-the-log).

### Playback across a restart

`SUPERVISOR_STARTED` changes every unit and clears the flood without any per-unit event. When the replay store applies it, it reloads the board at that event's instant and goes on from there, so the browser holds no rule for restarts. In the incident summary a restart ends an open flood, contact loss or service degradation, marked "ended by supervisor restart"; the start-up events that follow open new periods as recorded.

### Command ids

An equipment command writes an intent first and its outcome later. Both now carry the same `commandId` (a UUID, added to the payload as an optional field, so `EventCodec.SCHEMA_VERSION` stays 1 and older events decode with no id). The incident summary pairs the two by id. An intent with no recorded outcome is listed as "outcome not recorded" and never dropped; records from older logs without an id are listed unpaired.

### The Replay screen and the export

The Replay page has its own store, `ReplayStore`, which has no dependency on the live stream or the live board store, and it shows only a band, never the live header, so no control screen is one click away. Alarm and detail components are the live ones in a read-only mode. The incident export (CSV or JSON, at most 7 days) carries the incident summary plus the state changes and actions, and leaves out telemetry samples. The endpoints are in [Architecture §2.6](../architecture.md#26-replay-and-the-incident-export).

### Consequences

* **Good:** replay uses the live reducer, so a replayed board cannot disagree with the live one; seeking is one request; the server keeps nothing per viewer.
* **Good:** the live and replay paths share one message mapper on the server, so the stream and the event feed cannot drift apart.
* **Bad / Trade-off:** the gap rule is evaluated twice, on the server for a seek and in the browser during play. Both read the same recorded events and are tested against the same cases.
* **Bad / Trade-off:** values are 30-second samples. Replay never interpolates and says so on every tile.
* **Bad / Trade-off:** the first start after an upgrade folds the whole log, which is slower until the first snapshot exists.

## Verification

* [`GapRuleTest`] — the gap rule at, inside and past the threshold, and after a stop.
* [`BoardHistoryIT`] — the board at an instant, a fold from a snapshot equal to a fold from the start, a gap during downtime, a sample from before a restart not shown after it, and the lost-contact worked example (`theLostContactExampleMatchesEachRow`).
* [`ReplayFidelityIT`] — the live board equals the board at now and the fold of the whole log, and a restarted writer rebuilds the same board (`theLiveBoardEqualsBoardAtNowAndTheFoldFromTheStart`, `aRestartRebuildsTheSameBoardFromTheLog`, `aStartWithoutSnapshotsFoldsTheWholeLog`).
* [`SnapshotCodecTest`] and [`JpaSnapshotStoreIT`] — the snapshot round trip and the changeset.
* [`EquipmentControlTest`] and [`EventCodecTest`] — one command id on the intent and its outcome (`theIntentAndItsOutcomeShareOneCommandId`), and payloads without an id still decode (`aCommandPayloadWithoutAnIdDecodesWithNull`).
* [`IncidentSummaryTest`] — the summary, including commands paired by id and a restart ending open periods.
* [`replay-store.spec.ts`] — playback, the gap during play, the re-seek at a restart and live messages changing nothing (`liveStreamMessagesChangeNothingInReplay`).

[`BoardWriter`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`BoardWriter.start`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`BoardReducer`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducer.java
[`BoardHistory`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/BoardHistory.java
[`GapRule`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/GapRule.java
[`GapRuleTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/GapRuleTest.java
[`BoardHistoryIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/BoardHistoryIT.java
[`ReplayFidelityIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/ReplayFidelityIT.java
[`SnapshotCodecTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/SnapshotCodecTest.java
[`JpaSnapshotStoreIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/JpaSnapshotStoreIT.java
[`IncidentSummaryTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/IncidentSummaryTest.java
[`EquipmentControlTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/control/EquipmentControlTest.java
[`EventCodecTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/EventCodecTest.java
[`replay-store.spec.ts`]: ../../ui/src/app/features/replay/replay-store.spec.ts
