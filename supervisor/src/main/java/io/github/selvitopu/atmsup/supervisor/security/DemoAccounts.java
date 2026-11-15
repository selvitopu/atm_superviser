package io.github.selvitopu.atmsup.supervisor.security;

import java.util.List;

/** The demo accounts of Liquibase changeset 003, in the order they are listed and reported. */
public final class DemoAccounts {

    public static final List<DemoAccount> ALL = List.of(
            new DemoAccount("admin1", "ADMIN", "admin1-demo"),
            new DemoAccount("supervisor1", "SUPERVISOR", "supervisor1-demo"),
            new DemoAccount("demo1", "VIEWER", "demo1-demo"));

    private DemoAccounts() {
    }
}
