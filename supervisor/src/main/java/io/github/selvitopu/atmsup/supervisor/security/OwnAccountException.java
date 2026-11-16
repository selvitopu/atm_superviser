package io.github.selvitopu.atmsup.supervisor.security;

/** An administrator tried to change their own account, which could lock them out or sign them out mid-change. */
public class OwnAccountException extends RuntimeException {

    public OwnAccountException() {
        super("You cannot change your own account here");
    }
}
