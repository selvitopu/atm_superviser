package io.github.selvitopu.atmsup.supervisor.history;

import java.time.Instant;

/** When an event occurred and its {@code EventType} name: all the gap rule reads of a row. */
public record EventStamp(Instant occurredAt, String type) {
}
