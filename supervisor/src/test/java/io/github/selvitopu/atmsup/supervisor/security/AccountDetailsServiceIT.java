package io.github.selvitopu.atmsup.supervisor.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.selvitopu.atmsup.supervisor.LiquibaseDemoContext;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Signing in reads the accounts the Liquibase changelog created, here the demo accounts. */
@DataJpaTest(showSql = false, properties = "atm.demo-mode=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({AccountDetailsService.class, LiquibaseDemoContext.class})
class AccountDetailsServiceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Autowired
    AccountDetailsService accounts;

    @Autowired
    DataSource dataSource;

    @Test
    void loadsADemoAccountWithItsRole() {
        UserDetails admin = accounts.loadUserByUsername("admin1");

        assertThat(admin.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
        assertThat(admin.isEnabled()).isTrue();
    }

    @Test
    void signInIgnoresCaseAndSpaces() {
        assertThat(accounts.loadUserByUsername("  Admin1 ").getUsername()).isEqualTo("admin1");
    }

    @Test
    void aDisabledAccountCannotSignIn() {
        new JdbcTemplate(dataSource).update("update app_user set enabled = false where username = 'demo1'");

        assertThat(accounts.loadUserByUsername("demo1").isEnabled()).isFalse();
    }

    @Test
    void unknownUserIsNotFound() {
        assertThatThrownBy(() -> accounts.loadUserByUsername("nobody")).isInstanceOf(UsernameNotFoundException.class);
    }
}
