package io.github.selvitopu.atmsup.simulator.agent;

/**
 * Outcome of validating one SET variable binding: an SNMP error status ({@code PDU.noError} when the value may be
 * applied) and a human-readable reason for rejections.
 */
public record SetCheck(int errorStatus, String reason) {

    public static final SetCheck OK = new SetCheck(0, "");
}
