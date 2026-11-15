package io.github.selvitopu.atmsup.supervisor.security;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who is signed in. Sign-in and sign-out themselves are handled by the security filter chain. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @GetMapping("/me")
    public UserDto me(Authentication authentication) {
        return UserDto.of(authentication);
    }
}
