package io.github.selvitopu.atmsup.supervisor.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.selvitopu.atmsup.supervisor.LiquibaseDemoContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Account administration against the real schema and the seeded demo accounts. */
@DataJpaTest(showSql = false, properties = "atm.demo-mode=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(LiquibaseDemoContext.class)
class AdminUserServiceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Autowired
    AppUserRepository users;
    @Autowired
    TestEntityManager entities;

    private final LiveSessions sessions = mock(LiveSessions.class);
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private AdminUserService admin;

    @BeforeEach
    void service() {
        admin = new AdminUserService(users, encoder, sessions);
    }

    private AppUser reloaded(String username) {
        entities.flush();
        entities.clear();
        return users.findByUsername(username).orElseThrow();
    }

    @Test
    void listsTheAccountsByUsername() {
        assertThat(admin.list()).extracting(AdminUserDto::username, AdminUserDto::displayName, AdminUserDto::role,
                AdminUserDto::enabled).containsExactly(
                tuple("admin1", "Admin One", "ADMIN", true),
                tuple("demo1", "Demo One", "VIEWER", true),
                tuple("supervisor1", "Supervisor One", "SUPERVISOR", true));
    }

    @Test
    void promotesAViewerAndEndsTheirSessions() {
        AdminUserDto changed = admin.change("Demo1", new UserChange("SUPERVISOR", null), "admin1");

        assertThat(changed.role()).isEqualTo("SUPERVISOR");
        assertThat(reloaded("demo1").getRoles()).containsExactly("SUPERVISOR");
        verify(sessions).endSessionsOf("demo1");
    }

    @Test
    void disablingEndsTheirSessions() {
        admin.change("demo1", new UserChange(null, false), "admin1");

        assertThat(reloaded("demo1").isEnabled()).isFalse();
        verify(sessions).endSessionsOf("demo1");
    }

    @Test
    void aChangeThatChangesNothingLeavesTheirSessions() {
        admin.change("demo1", new UserChange("VIEWER", true), "admin1");

        verifyNoInteractions(sessions);
    }

    @Test
    void anAdminCannotChangeTheirOwnAccount() {
        assertThatThrownBy(() -> admin.change("admin1", new UserChange("VIEWER", null), "admin1"))
                .isInstanceOf(OwnAccountException.class);
        assertThatThrownBy(() -> admin.change("admin1", new UserChange(null, false), "admin1"))
                .isInstanceOf(OwnAccountException.class);
        assertThatThrownBy(() -> admin.setPassword("admin1", "fresh-pass-123", "admin1"))
                .isInstanceOf(OwnAccountException.class);
    }

    @Test
    void setsAPasswordThatSignsInAndEndsTheirSessions() {
        admin.setPassword("demo1", "fresh-pass-123", "admin1");

        assertThat(encoder.matches("fresh-pass-123", reloaded("demo1").getPasswordHash())).isTrue();
        verify(sessions).endSessionsOf("demo1");
    }

    @Test
    void aNewPasswordFollowsTheRegistrationRule() {
        assertThatThrownBy(() -> admin.setPassword("demo1", "short", "admin1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Passwords are 10 to 72 characters, and accented letters count double");
    }

    @Test
    void refusesAnUnknownRole() {
        assertThatThrownBy(() -> admin.change("demo1", new UserChange("ROOT", null), "admin1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Roles are ADMIN, SUPERVISOR and VIEWER");
    }

    @Test
    void anUnknownUserIsNotFound() {
        assertThatThrownBy(() -> admin.change("nobody", new UserChange(null, false), "admin1"))
                .isInstanceOf(NoSuchUserException.class);
    }
}
