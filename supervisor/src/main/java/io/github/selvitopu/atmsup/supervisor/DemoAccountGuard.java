package io.github.selvitopu.atmsup.supervisor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Refuses to start outside demo mode while a demo account is still enabled with its documented default password. The
 * Liquibase demo changeset runs once, so a database first started in demo mode keeps those accounts; this check runs
 * at every start, like the check of the demo SNMP passwords. Taking a {@link JdbcTemplate} makes it run after
 * Liquibase.
 */
@Component
public final class DemoAccountGuard {

    /** The demo accounts and their documented default passwords, in the order they are reported. */
    private static final List<Map.Entry<String, String>> DEMO_ACCOUNTS = List.of(
            Map.entry("admin1", "admin1-demo"),
            Map.entry("supervisor1", "supervisor1-demo"),
            Map.entry("demo1", "demo1-demo"));

    DemoAccountGuard(JdbcTemplate jdbc, Environment environment) {
        check(jdbc, environment.getProperty("atm.demo-mode", Boolean.class, false));
    }

    /** Throws if, outside demo mode, an enabled demo account still accepts its default password. */
    public static void check(JdbcTemplate jdbc, boolean demoMode) {
        if (demoMode) {
            return;
        }
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, String> account : DEMO_ACCOUNTS) {
            List<String> hashes = jdbc.queryForList(
                    "select password_hash from app_user where username = ? and enabled", String.class, account.getKey());
            if (!hashes.isEmpty() && acceptsDefault(encoder, account.getValue(), hashes.getFirst())) {
                found.add(account.getKey());
            }
        }
        if (!found.isEmpty()) {
            throw new IllegalStateException("Demo accounts with their default passwords exist outside demo mode: "
                    + String.join(", ", found) + ". Disable them or change their passwords, or run in demo mode"
                    + " (atm.demo-mode=true).");
        }
    }

    private static boolean acceptsDefault(PasswordEncoder encoder, String password, String hash) {
        try {
            return encoder.matches(password, hash);
        } catch (IllegalArgumentException notADelegatingHash) {
            return false;
        }
    }
}
