package io.github.selvitopu.atmsup.mib;

/** An enum whose constants map to the INTEGER codes used on the wire in DEMO-ATM-TECHSUP-MIB. */
public interface SnmpEnum {

    /** The INTEGER value used in the MIB. */
    int code();

    /**
     * Looks up the constant of {@code type} with the given code.
     *
     * @throws IllegalArgumentException if no constant has that code
     */
    static <E extends Enum<E> & SnmpEnum> E fromCode(Class<E> type, int code) {
        for (E constant : type.getEnumConstants()) {
            if (constant.code() == code) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " code " + code);
    }
}
