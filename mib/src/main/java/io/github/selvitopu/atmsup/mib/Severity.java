package io.github.selvitopu.atmsup.mib;

/** AtmTsSeverity (ITU X.733 / RFC 3877). The codes are wire values, not an ordering; see {@link #moreSevereThan}. */
public enum Severity implements SnmpEnum {
    CLEARED(1, 0),
    INDETERMINATE(2, 1),
    CRITICAL(3, 5),
    MAJOR(4, 4),
    MINOR(5, 3),
    WARNING(6, 2);

    private final int code;
    private final int rank;

    Severity(int code, int rank) {
        this.code = code;
        this.rank = rank;
    }

    @Override
    public int code() {
        return code;
    }

    /** True if this severity is strictly worse than {@code other}: CRITICAL > MAJOR > MINOR > WARNING > INDETERMINATE > CLEARED. */
    public boolean moreSevereThan(Severity other) {
        return rank > other.rank;
    }
}
