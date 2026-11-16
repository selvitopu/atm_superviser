package io.github.selvitopu.atmsup.supervisor.security;

/** One account as the administration screen shows it: its single role and whether it may sign in. */
public record AdminUserDto(String username, String displayName, String role, boolean enabled) {
}
