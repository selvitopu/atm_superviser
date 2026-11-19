package io.github.selvitopu.atmsup.supervisor.impact;

/** A unit that is not a candidate for the N-1 question, and why. */
public record OutOfService(String unit, OutReason reason) {
}
