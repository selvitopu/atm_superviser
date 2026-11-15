package io.github.selvitopu.atmsup.supervisor.security;

/** What a new user types on the registration screen; {@link AccountService} validates and normalises it. */
public record RegisterRequest(String username, String displayName, String password) {
}
