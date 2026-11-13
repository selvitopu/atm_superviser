package io.github.selvitopu.atmsup.inventory;

/** Where agents send SNMP traps. */
public record TrapTarget(String host, int port) {
}
