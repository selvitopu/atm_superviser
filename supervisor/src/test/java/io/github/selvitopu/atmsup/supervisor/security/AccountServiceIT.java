package io.github.selvitopu.atmsup.supervisor.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.selvitopu.atmsup.supervisor.LiquibaseDemoContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Registration against the real schema, next to the seeded demo accounts. */
@DataJpaTest(showSql = false, properties = "atm.demo-mode=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(LiquibaseDemoContext.class)
class AccountServiceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Autowired
    AppUserRepository users;

    private AccountService accounts;

    @BeforeEach
    void service() {
        accounts = new AccountService(users, PasswordEncoderFactories.createDelegatingPasswordEncoder(), true);
    }

    @Test
    void registersAViewerWithANormalisedName() {
        UserDto created = accounts.register(new RegisterRequest("  NewUser ", "New User", "long-enough-pw"));

        assertThat(created).isEqualTo(new UserDto("newuser", "New User", java.util.List.of("VIEWER")));
        AppUser stored = users.findByUsername("newuser").orElseThrow();
        assertThat(stored.getRoles()).containsExactly("VIEWER");
        assertThat(stored.getPasswordHash()).startsWith("{bcrypt}");
        assertThat(stored.isEnabled()).isTrue();
    }

    @Test
    void aTakenNameIsRefusedWhateverItsCase() {
        assertThatThrownBy(() -> accounts.register(new RegisterRequest("ADMIN1", "Another Admin", "long-enough-pw")))
                .isInstanceOf(UsernameTakenException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ab         | Short Name | long-enough-pw
            a b        | Spaced     | long-enough-pw
            okname     | '   '      | long-enough-pw
            okname     | Ok Name    | short
            samename1  | Same       | SameName1
            """)
    void invalidInputIsRefused(String username, String displayName, String password) {
        assertThatThrownBy(() -> accounts.register(new RegisterRequest(username, displayName, password)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registrationCanBeSwitchedOff() {
        AccountService closed = new AccountService(users, PasswordEncoderFactories.createDelegatingPasswordEncoder(),
                false);

        assertThatThrownBy(() -> closed.register(new RegisterRequest("newuser", "New User", "long-enough-pw")))
                .isInstanceOf(RegistrationClosedException.class);
    }
}
