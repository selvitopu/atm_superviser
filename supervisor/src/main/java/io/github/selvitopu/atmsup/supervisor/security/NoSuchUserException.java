package io.github.selvitopu.atmsup.supervisor.security;

/** The account an administrator asked about does not exist. */
public class NoSuchUserException extends RuntimeException {

    public NoSuchUserException() {
        super("No such user");
    }
}
