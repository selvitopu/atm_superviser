package io.github.selvitopu.atmsup.mib;

/** atmTsEqCommand. Reads back as NONE. */
public enum Command implements SnmpEnum {
    NONE(1),
    BECOME_ACTIVE(2),
    RESTART(3),
    RESET_COUNTERS(4);

    private final int code;

    Command(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
