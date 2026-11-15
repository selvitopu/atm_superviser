package io.github.selvitopu.atmsup.supervisor.security;

public class UsernameTakenException extends RuntimeException {

    public UsernameTakenException() {
        super("That username is taken");
    }
}
