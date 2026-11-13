package io.github.selvitopu.atmsup.supervisor.board;

import java.time.Instant;

/** Reachability of a device; both instants are nullable (never reached / never lost). */
public record Contact(boolean reachable, Instant lastContactAt, Instant lostAt) {
}
