# ADR-0005: Server-Sent Events (SSE) with Bounded Per-Client Queues and Ring-Buffer Resume over WebSockets

- **Status:** Accepted (implemented)
- **Date:** 2026-11-13
- **Deciders:** Project maintainers

## Context and Problem Statement

The Angular operator console needs sub-second server-to-browser updates for equipment state changes, derived service
states, alarm transitions, and live telemetry values, while operator actions (acknowledging alarms, sending equipment
commands) are ordinary HTTP request/response calls.

Writing to browser sockets synchronously on the [`BoardWriter`] thread would create an availability hazard
([Hazard H5](../safety/hazard-log.md#h5-a-slow-browser-blocks-supervision)): one slow or half-open browser connection
could stall the writer and block supervision for the whole centre. Conversely, silently dropping durable state events
when a client falls behind would leave that operator's board out of date without warning.

## Decision Drivers

- **Keep [`BoardWriter`] off network I/O:** the board writer must never block on client socket writes.
- **Never drop a durable event silently:** a slow client must either receive every durable event in order or be
  disconnected and resynchronised.
- **Standard HTTP semantics for actions:** alarm acknowledgements and commands fit standard REST endpoints, status
  codes, and RFC 9457 `ProblemDetail` responses ([`ApiExceptionHandler`]).

## Considered Options

1. **Unidirectional Server-Sent Events (`/api/v1/stream`) backed by [`SseBroadcaster`] (1,000-event ring buffer) and
   per-client [`SseClient`] (bounded 256-event durable queue and coalesced volatile telemetry), paired with REST
   endpoints for operator actions** (chosen).
2. **Bidirectional WebSockets (`STOMP` or raw JSON frames).**
3. **Periodic HTTP polling of `GET /api/v1/board` from the browser.**

## Decision Outcome

Chosen option: **Option 1.**

1. **Non-blocking enqueue from [`BoardWriter`]:**
   - After a transaction commits, [`BoardWriter`] publishes the committed [`StoredEvent`] list and any volatile
     [`Telemetry`] to [`SseBroadcaster`].
   - [`SseBroadcaster`] appends durable events (`equipment`, `service`, `alarm`, `flood`, `supervisor`, each carrying
     `id = event_log.id`) to a bounded 1,000-event ring buffer and offers them without blocking into each connected
     [`SseClient`].
2. **Per-client bounded durable queue and coalesced volatile channel ([`SseClient`]):**
   - Each [`SseClient`] holds a bounded queue (256 messages by default) for durable events (`id: <event_log.id>`).
   - Volatile `telemetry` messages (which have no SSE `id`) are coalesced into a per-device `latest` map on the client
     (`telemetryIsCoalescedLatestOnly`), and 15 s `heartbeat` frames share one slot.
   - A virtual thread per client drains durable events first and sends volatile telemetry or heartbeats only when the
     durable queue is empty.
3. **Disconnect on overflow and resume or resync:**
   - If a client's 256-event durable queue is full, `offer` returns `false` at once and [`SseBroadcaster`] closes that
     client's stream (`overflowDisconnectsInsteadOfDropping`), incrementing `atmsup.sse.disconnects{reason="overflow"}`.
   - When the browser reconnects with `Last-Event-ID`, [`SseBroadcaster`] replays the missed durable events from the
     1,000-event ring buffer. If the gap is older than the ring buffer, the server sends `event: resync`, and the
     Angular client reloads `GET /api/v1/board`.
   - If the stream stays down for more than 10 s, the Angular board shows the hatched `"DATA NOT LIVE"` overlay
     ([`data-not-live-overlay.spec.ts`]).

### Consequences

- **Good:** [`BoardWriter`] never blocks on browser I/O, and one slow client cannot delay other connected operators
  (`slowClientDoesNotDelayOthers`).
- **Good:** volatile telemetry bursts can never evict or overflow durable alarm and state transitions.
- **Trade-off:** the total number of simultaneously connected SSE clients is not yet bounded
  ([Hazard H5](../safety/hazard-log.md#h5-a-slow-browser-blocks-supervision), planned for `v0.2`).

## Verification

- [`SseBroadcasterTest`]: ring-buffer `Last-Event-ID` resume, `resync` when the gap exceeds the ring buffer,
  `slowClientDoesNotDelayOthers`, and `overflowDisconnectsInsteadOfDropping`.
- [`SseClientTest`]: `offersNeverBlockAndAFullQueueRefusesTheNextDurableMessage` and `telemetryIsCoalescedLatestOnly`.
- [`data-not-live-overlay.spec.ts`]: the browser `"DATA NOT LIVE"` overlay when the SSE stream is disconnected.

[`ApiExceptionHandler`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/ApiExceptionHandler.java
[`BoardWriter`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`SseBroadcasterTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/api/SseBroadcasterTest.java
[`SseBroadcaster`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseBroadcaster.java
[`SseClientTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/api/SseClientTest.java
[`SseClient`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseClient.java
[`StoredEvent`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StoredEvent.java
[`Telemetry`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/Telemetry.java
[`data-not-live-overlay.spec.ts`]: ../../ui/src/app/features/board/data-not-live-overlay/data-not-live-overlay.spec.ts
