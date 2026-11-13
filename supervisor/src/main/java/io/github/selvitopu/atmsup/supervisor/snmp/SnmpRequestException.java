package io.github.selvitopu.atmsup.supervisor.snmp;

/** A failed SNMP request or an unusable answer; {@link #getMessage()} is {@code KIND: detail}. */
public class SnmpRequestException extends RuntimeException {

    /** Why the request failed. */
    public enum Kind { TIMEOUT, AUTH_FAILURE, ERROR_STATUS, MALFORMED }

    private final Kind kind;
    private final String detail;

    public SnmpRequestException(Kind kind, String detail) {
        super(kind + ": " + detail);
        this.kind = kind;
        this.detail = detail;
    }

    public Kind kind() {
        return kind;
    }

    public String detail() {
        return detail;
    }
}
