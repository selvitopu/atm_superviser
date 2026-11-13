package io.github.selvitopu.atmsup.mib;

/** atmTsEqRole. */
public enum Role implements SnmpEnum {
    STANDALONE(1),
    ACTIVE(2),
    STANDBY(3);

    private final int code;

    Role(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
