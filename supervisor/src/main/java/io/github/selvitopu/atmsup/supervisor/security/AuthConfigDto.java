package io.github.selvitopu.atmsup.supervisor.security;

import java.util.List;

/** What the sign-in screen needs before anyone is signed in. The demo accounts are listed in demo mode only. */
public record AuthConfigDto(boolean demoMode, boolean registrationEnabled, List<DemoAccount> demoAccounts) {
}
