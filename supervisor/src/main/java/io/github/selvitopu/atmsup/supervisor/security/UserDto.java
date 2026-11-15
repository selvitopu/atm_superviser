package io.github.selvitopu.atmsup.supervisor.security;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** The signed-in user as the UI sees it; roles without the {@code ROLE_} prefix, sorted. */
public record UserDto(String username, String displayName, List<String> roles) {

    private static final String PREFIX = "ROLE_";

    static UserDto of(Authentication authentication) {
        String displayName = authentication.getPrincipal() instanceof AccountUser account
                ? account.getDisplayName() : authentication.getName();
        List<String> roles = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(PREFIX)).map(a -> a.substring(PREFIX.length())).sorted().toList();
        return new UserDto(authentication.getName(), displayName, roles);
    }
}
