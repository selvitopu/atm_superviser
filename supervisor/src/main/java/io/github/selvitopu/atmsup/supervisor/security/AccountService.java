package io.github.selvitopu.atmsup.supervisor.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-registration. A new account is always a read-only VIEWER; an administrator grants more. The username is
 * trimmed and lower-cased before anything else, so "Admin1" and "admin1" can never become two accounts.
 */
@Service
public class AccountService {

    static final String NEW_ACCOUNT_ROLE = "VIEWER";
    static final int MAX_PASSWORD_BYTES = 72;
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,31}$");

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final boolean registrationEnabled;

    public AccountService(AppUserRepository users, PasswordEncoder passwords,
                          @Value("${atm.security.registration-enabled:true}") boolean registrationEnabled) {
        this.users = users;
        this.passwords = passwords;
        this.registrationEnabled = registrationEnabled;
    }

    public boolean registrationEnabled() {
        return registrationEnabled;
    }

    /** Creates a VIEWER account; throws {@link IllegalArgumentException} with the broken rule for invalid input. */
    @Transactional
    public UserDto register(RegisterRequest request) {
        if (!registrationEnabled) {
            throw new RegistrationClosedException();
        }
        String username = AccountDetailsService.normalise(request.username());
        String displayName = request.displayName() == null ? "" : request.displayName().trim();
        String password = request.password() == null ? "" : request.password();
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("Usernames are 3 to 32 characters: lower-case letters, digits, dots,"
                    + " dashes and underscores, starting with a letter or digit");
        }
        if (displayName.isEmpty() || displayName.length() > 64 || displayName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Display names are 1 to 64 characters without control characters");
        }
        checkPassword(username, password);
        if (users.findByUsername(username).isPresent()) {
            throw new UsernameTakenException();
        }
        try {
            users.saveAndFlush(new AppUser(username, displayName, passwords.encode(password), Set.of(NEW_ACCOUNT_ROLE)));
        } catch (DataIntegrityViolationException raceWithAnotherRegistration) {
            throw new UsernameTakenException();
        }
        return new UserDto(username, displayName, List.of(NEW_ACCOUNT_ROLE));
    }

    /** The password rule, for registration and for a password an administrator sets. */
    static void checkPassword(String username, String password) {
        // bcrypt hashes at most 72 bytes; accented letters take two bytes in UTF-8
        if (password == null || password.length() < 10
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("Passwords are 10 to 72 characters, and accented letters count double");
        }
        if (password.equalsIgnoreCase(username)) {
            throw new IllegalArgumentException("The password must not be the username");
        }
    }
}
