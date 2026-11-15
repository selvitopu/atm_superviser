package io.github.selvitopu.atmsup.supervisor.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.selvitopu.atmsup.supervisor.security.DemoAccountGuard;
import io.github.selvitopu.atmsup.supervisor.LiquibaseDemoContext;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The Liquibase changelog in demo mode: roles plus the three documented demo accounts. */
@DataJpaTest(showSql = false, properties = "atm.demo-mode=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(LiquibaseDemoContext.class)
class DemoSeedIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Autowired
    DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void jdbc() {
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void rolesExist() {
        assertThat(jdbc.queryForList("select name from role order by name", String.class))
                .containsExactly("ADMIN", "SUPERVISOR", "VIEWER");
    }

    @Test
    void demoUsersHaveOneRoleEach() {
        assertThat(jdbc.queryForList("select u.username || ':' || r.role_name || ':' || u.enabled from app_user u"
                + " join user_role r on r.user_id = u.id order by u.username", String.class))
                .containsExactly("admin1:ADMIN:true", "demo1:VIEWER:true", "supervisor1:SUPERVISOR:true");
    }

    @Test
    void demoUsersHaveTheirDocumentedPasswords() {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

        for (String user : List.of("admin1", "supervisor1", "demo1")) {
            String hash = jdbc.queryForObject("select password_hash from app_user where username = ?", String.class,
                    user);
            assertThat(hash).as(user).startsWith("{bcrypt}$2a$10$");
            assertThat(encoder.matches(user + "-demo", hash)).as(user).isTrue();
        }
    }

    @Test
    void usernamesMustBeLowerCase() {
        assertThatThrownBy(() -> jdbc.update("insert into app_user (username, display_name, password_hash)"
                + " values ('Admin2', 'Admin Two', 'x')")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void outsideDemoModeTheDefaultDemoAccountsRefuseStartup() {
        assertThatThrownBy(() -> DemoAccountGuard.check(jdbc, false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin1, supervisor1, demo1");
    }

    @Test
    void inDemoModeTheDemoAccountsAreAllowed() {
        assertThatCode(() -> DemoAccountGuard.check(jdbc, true)).doesNotThrowAnyException();
    }

    @Test
    void disabledOrChangedDemoAccountsNoLongerBlockStartup() {
        jdbc.update("update app_user set enabled = false where username = 'admin1'");
        jdbc.update("update app_user set password_hash = ? where username = 'supervisor1'",
                PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("a-new-password"));
        jdbc.update("update app_user set password_hash = 'not-a-delegating-hash' where username = 'demo1'");

        assertThatCode(() -> DemoAccountGuard.check(jdbc, false)).doesNotThrowAnyException();
    }
}
