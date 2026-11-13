package io.github.selvitopu.atmsup.simulator.model;

/** Asks a device to apply {@code fault}; {@code target} (nullable) names the part it concerns, e.g. a sensor. */
public record FaultRequest(Fault fault, String target) {
}
