package io.github.selvitopu.atmsup.supervisor.history;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Liquibase settings in application.yml on their own, without {@code LiquibaseDemoContext}: if that post-processor
 * is ever missed, demo mode seeds no accounts (fail closed), and Liquibase never sends usage analytics.
 */
@DataJpaTest(showSql = false, properties = "atm.demo-mode=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class LiquibaseDefaultsIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Autowired
    DataSource dataSource;

    @Autowired
    SpringLiquibase liquibase;

    @Test
    void withoutTheDemoContextNoAccountsAreSeeded() {
        assertThat(liquibase.getContexts()).isEqualTo("default");
        assertThat(new JdbcTemplate(dataSource).queryForObject("select count(*) from app_user", Integer.class)).isZero();
    }

    @Test
    void liquibaseAnalyticsAreOff() {
        assertThat(liquibase.getAnalyticsEnabled()).isFalse();
    }
}
