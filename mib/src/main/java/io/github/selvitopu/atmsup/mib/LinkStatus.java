package io.github.selvitopu.atmsup.mib;

/** Link and transmitter/receiver status values (atmTsVcsRadioLinkStatus and the atmTsRadio status columns). */
public enum LinkStatus implements SnmpEnum {
    UP(1),
    DOWN(2);

    private final int code;

    LinkStatus(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
