package io.github.selvitopu.atmsup.mib;

/** atmTsSdpsSensorInputStatus. */
public enum SensorInputStatus implements SnmpEnum {
    RECEIVING(1),
    LOST(2),
    EXCLUDED(3);

    private final int code;

    SensorInputStatus(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
