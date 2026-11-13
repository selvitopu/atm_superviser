package io.github.selvitopu.atmsup.mib;

/** atmTsEqLastCommandResult. */
public enum CommandResult implements SnmpEnum {
    NONE(1),
    IN_PROGRESS(2),
    SUCCESS(3),
    REJECTED(4);

    private final int code;

    CommandResult(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }
}
