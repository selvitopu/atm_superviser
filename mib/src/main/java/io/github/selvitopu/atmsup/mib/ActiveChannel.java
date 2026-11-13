package io.github.selvitopu.atmsup.mib;

/** atmTsRadarActiveChannel. */
public enum ActiveChannel implements SnmpEnum {
    CHANNEL_A(1),
    CHANNEL_B(2);

    private final int code;

    ActiveChannel(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
