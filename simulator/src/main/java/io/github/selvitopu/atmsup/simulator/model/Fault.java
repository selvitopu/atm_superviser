package io.github.selvitopu.atmsup.simulator.model;

/** A fault the console (or a test) can inject into a simulated device. */
public enum Fault {
    FAIL, DEGRADE, RECOVER, CHANNEL_A_FAIL, CHANNEL_B_FAIL, SENSOR_LOST, UNREACHABLE, DROP_TRAPS
}
