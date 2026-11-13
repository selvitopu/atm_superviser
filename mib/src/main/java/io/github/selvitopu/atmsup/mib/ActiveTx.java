package io.github.selvitopu.atmsup.mib;

/** atmTsRadioActiveTx. */
public enum ActiveTx implements SnmpEnum {
    MAIN(1),
    STANDBY(2);

    private final int code;

    ActiveTx(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
