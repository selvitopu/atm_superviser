package io.github.selvitopu.atmsup.mib;

/** AtmTsEquipmentStatus. Health only; maintenance is a separate flag, and UNKNOWN exists only in the supervisor. */
public enum EquipmentStatus implements SnmpEnum {
    OPERATIONAL(1),
    DEGRADED(2),
    FAILED(3);

    private final int code;

    EquipmentStatus(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
