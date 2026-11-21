# ADR-0005: Server-Sent Events (SSE) with Bounded Per-Client Queues and Ring-Buffer Resume over WebSockets

* **Status:** Accepted — Implemented in `v0.1.1`
* **Date:** 2026-11-13 (revised 2026-11-15 for `v0.1.1`)
* **Deciders:** Project maintainers

## Context and Problem Statement

The Angular operator console needs sub-second server-to-browser push updates for equipment state transitions, derived service states, alarm changes, and live telemetry gauges, while operator actions (acknowledging alarms in `v0.1.1`, issuing control commands in `v0.3`) are discrete request/response operations.

Furthermore, a naive push implementation that writes to browser sockets synchronously on the [`BoardWriter`] thread creates a severe availability hazard ([Hazard H5](../safety/hazard-log.md#h5-a-slow-browser-blocks-supervision)): one slow or half-open browser connection can block the writer and stall supervision for the entire centre. Conversely, silently dropping durable state events when a client falls behind leaves that operator's board showing a false picture.

## Decision Drivers

* **Decouple [`BoardWriter`] from network I/O:** The board writer must never block on client socket writes.
* **Never silently drop a durable event:** A slow client must either receive every durable event in order or be explicitly disconnected and resynchronised.
* **Standard HTTP semantics for mutations:** Alarm acknowledgements and commands benefit from standard REST endpoints, status codes, and RFC 9457 `ProblemDetail` responses ([`ApiExceptionHandler`]).

## Considered Options

1. **Unidirectional Server-Sent Events (`/api/v1/stream`) backed by [`SseBroadcaster`] (1,000-event ring buffer) and per-client [`SseClient`] (bounded 256-event durable queue + coalesced volatile telemetry), paired with REST `POST` endpoints for operator actions** *(Chosen)*
2. **Bidirectional WebSockets (`STOMP` or raw JSON frames)**
3. **Periodic HTTP polling of `GET /api/v1/board` from the browser**

## Decision Outcome

Chosen option: **Option 1.**

### How It Works in `v0.1.1`

1. **Non-Blocking Enqueue from [`BoardWriter`]:**
   * After a transaction commits, [`BoardWriter`] passes the committed [`StoredEvent`] list and optional volatile [`Telemetry`] to [`SseBroadcaster`].
   * [`SseBroadcaster`] appends durable events (`equipment`, `service`, `alarm`, `supervisor`, each carrying `id = event_log.id`) to a bounded **1,000-event ring buffer** and offers them non-blockingly into each connected [`SseClient`].
2. **Per-Client Bounded Durable Queue + Coalesced Volatile Channel ([`SseClient`]):**
   * Each [`SseClient`] holds a bounded queue (`256` messages by default) for durable events (`id: <event_log.id>`).
   * Volatile `telemetry` messages (which have **no SSE `id`**) are coalesced into a per-device `latest` map on the client (`telemetryIsCoalescedLatestOnly`), and 15 s `heartbeat` frames are kept in a single slot.
   * A background sender drains durable events first and flushes volatile telemetry/heartbeats only when the durable queue is empty.
3. **Disconnect-on-Overflow & Resume / Resync:**
   * If a client's 256-event durable queue is full, `offer` returns `false` without blocking and [`SseBroadcaster`] immediately closes that client's stream (`overflowDisconnectsInsteadOfDropping`), incrementing `atmsup.sse.disconnects{reason="overflow"}`.
   * When the browser reconnects with `Last-Event-ID`, [`SseBroadcaster`] replays the missed durable events from the 1,000-event ring buffer. If the gap is older than the ring buffer, the server sends `event: resync`, and the Angular client refetches `GET /api/v1/board`.
   * If the stream stays down for more than 10 s, the Angular board displays the hatched `"DATA NOT LIVE"` overlay ([`data-not-live-overlay.spec.ts`]).

### Consequences

* **Good:** [`BoardWriter`] never blocks on browser I/O, and one slow client cannot delay other connected operators (`slowClientDoesNotDelayOthers`).
* **Good:** Volatile telemetry bursts can never evict or overflow durable alarm/state transitions.
* **Bad / Trade-off:** The total number of simultaneously connected SSE clients is not yet bounded in `v0.1.1` (noted in [Hazard H5](../safety/hazard-log.md#h5-a-slow-browser-blocks-supervision), planned for `v0.2`).

## Verification

* [`SseBroadcasterTest`] — Verifies ring-buffer `Last-Event-ID` resume, `resync` when the gap exceeds the ring buffer, `slowClientDoesNotDelayOthers`, and `overflowDisconnectsInsteadOfDropping`.
* [`SseClientTest`] — Verifies `offersNeverBlockAndAFullQueueRefusesTheNextDurableMessage` and `telemetryIsCoalescedLatestOnly`.
* [`data-not-live-overlay.spec.ts`] — Verifies the browser `"DATA NOT LIVE"` overlay when the SSE stream is disconnected.

[BoardWriter]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[StoredEvent]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StoredEvent.java
[Telemetry]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/Telemetry.java
[SseBroadcaster]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseBroadcaster.java
[SseClient]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseClient.java
[ApiExceptionHandler]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/ApiExceptionHandler.java
[SseBroadcasterTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/api/SseBroadcasterTest.java
[SseClientTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/api/SseClientTest.java
[data-not-live-overlay.spec.ts]: ../../ui/src/app/features/board/data-not-live-overlay/data-not-live-overlay.spec.ts
[`ApiExceptionHandler`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/ApiExceptionHandler.java
[`BoardWriter`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`SseBroadcasterTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/api/SseBroadcasterTest.java
[`SseBroadcaster`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseBroadcaster.java
[`SseClientTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/api/SseClientTest.java
[`SseClient`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseClient.java
[`StoredEvent`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StoredEvent.java
[`Telemetry`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/Telemetry.java
[`data-not-live-overlay.spec.ts`]: ../../ui/src/app/features/board/data-not-live-overlay/data-not-live-overlay.spec.ts
