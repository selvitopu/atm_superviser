package io.github.selvitopu.atmsup.supervisor.security;

/** An administrator's change to an account; a null field is left as it is. */
public record UserChange(String role, Boolean enabled) {
}
