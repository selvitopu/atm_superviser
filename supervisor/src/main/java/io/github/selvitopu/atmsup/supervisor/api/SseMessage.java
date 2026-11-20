package io.github.selvitopu.atmsup.supervisor.api;

/**
 * One server-sent event: the SSE event {@code name}, the {@code id} (the event-log id of a durable event; null for
 * telemetry, heartbeat and resync) and the {@code data} object, written as JSON. Immutable, so one instance is shared
 * by the ring buffer and every client.
 */
public record SseMessage(String name, Long id, Object data) {
}
