package io.github.selvitopu.atmsup.supervisor.snmp;

/** A failed SNMP request or an unusable answer; {@link #getMessage()} is {@code KIND: detail}. */
public class SnmpRequestException extends RuntimeException {

    /** Why the request failed. */
    public enum Kind { TIMEOUT, AUTH_FAILURE, ERROR_STATUS, MALFORMED }

    private final Kind kind;
    private final String detail;
    private final int errorStatus;

    public SnmpRequestException(Kind kind, String detail) {
        this(kind, detail, 0);
    }

    /** {@code errorStatus} is the agent's SNMP error status for {@link Kind#ERROR_STATUS}, otherwise 0 (noError). */
    public SnmpRequestException(Kind kind, String detail, int errorStatus) {
        super(kind + ": " + detail);
        this.kind = kind;
        this.detail = detail;
        this.errorStatus = errorStatus;
    }

    public int errorStatus() {
        return errorStatus;
    }

    public Kind kind() {
        return kind;
    }

    public String detail() {
        return detail;
    }
}
