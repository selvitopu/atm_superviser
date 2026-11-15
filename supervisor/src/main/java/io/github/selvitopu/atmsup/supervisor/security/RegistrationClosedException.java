package io.github.selvitopu.atmsup.supervisor.security;

public class RegistrationClosedException extends RuntimeException {

    public RegistrationClosedException() {
        super("Registration is switched off");
    }
}
