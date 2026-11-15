package io.github.selvitopu.atmsup.supervisor.security;

import java.util.Locale;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Loads accounts for sign-in. Names are compared trimmed and lower-case, so "Admin1" signs in as admin1. */
@Service
public class AccountDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AccountDetailsService(AppUserRepository users) {
        this.users = users;
    }

    static String normalise(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser user = users.findByUsername(normalise(username))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        return new AccountUser(user.getUsername(), user.getPasswordHash(), user.isEnabled(), user.getDisplayName(),
                user.getRoles().stream().sorted().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
    }
}
