package io.github.selvitopu.atmsup.supervisor.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** The signed-in principal: Spring's {@link User} plus the display name, so the session needs no lookup for it. */
public final class AccountUser extends User {

    private final String displayName;

    AccountUser(String username, String passwordHash, boolean enabled, String displayName,
                Collection<? extends GrantedAuthority> authorities) {
        super(username, passwordHash, enabled, true, true, true, authorities);
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
