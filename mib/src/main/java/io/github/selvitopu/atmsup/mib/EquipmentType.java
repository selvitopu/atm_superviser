package io.github.selvitopu.atmsup.mib;

/** atmTsEqType. */
public enum EquipmentType implements SnmpEnum {
    RADAR(1),
    ADSB(2),
    SDPS(3),
    FDPS(4),
    VCS(5),
    RADIO(6),
    RECORDER(7);

    private final int code;

    EquipmentType(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
