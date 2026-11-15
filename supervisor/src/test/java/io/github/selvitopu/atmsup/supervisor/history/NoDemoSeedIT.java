package io.github.selvitopu.atmsup.supervisor.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.selvitopu.atmsup.supervisor.LiquibaseDemoContext;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Outside demo mode the changelog creates the roles but no accounts, even on a fresh database. */
@DataJpaTest(showSql = false, properties = "atm.demo-mode=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(LiquibaseDemoContext.class)
class NoDemoSeedIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Autowired
    DataSource dataSource;

    @Test
    void rolesExistWithoutDemoUsers() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForList("select name from role order by name", String.class))
                .containsExactly("ADMIN", "SUPERVISOR", "VIEWER");
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isZero();
    }
}
