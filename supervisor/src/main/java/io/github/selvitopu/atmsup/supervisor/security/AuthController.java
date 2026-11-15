package io.github.selvitopu.atmsup.supervisor.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Who is signed in, the sign-in screen's configuration and registration. Sign-in and sign-out are filter-chain endpoints. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AccountService accounts;
    private final boolean demoMode;

    public AuthController(AccountService accounts, @Value("${atm.demo-mode:false}") boolean demoMode) {
        this.accounts = accounts;
        this.demoMode = demoMode;
    }

    @GetMapping("/me")
    public UserDto me(Authentication authentication) {
        return UserDto.of(authentication);
    }

    @GetMapping("/config")
    public AuthConfigDto config() {
        return new AuthConfigDto(demoMode, accounts.registrationEnabled(), demoMode ? DemoAccounts.ALL : List.of());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto register(@RequestBody RegisterRequest request) {
        return accounts.register(request);
    }
}
