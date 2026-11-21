# ADR-0009: PostgreSQL 18 with a Liquibase Changelog and Demo-Only Seed Accounts

- **Status:** Accepted (implemented)
- **Date:** 2026-11-15
- **Deciders:** Project maintainers

## Context and Problem Statement

`v0.1` stored the event log and alarms in MySQL 8.4, with the schema created by a Flyway migration. Two things the
supervisor needs were awkward there:

1. **One open alarm per identity.** MySQL has no partial unique indexes, so the rule "at most one alarm per
   `(equipment, code, instance)` that is not `CLOSED`" needed an extra `open_key` column, set by the application while
   an alarm is open and cleared when it closes.
2. **Event payloads.** The full-state payloads of `event_log` ([ADR-0004](0004-full-state-event-log-and-upsert-reducer.md))
   are JSON documents, and replay and export queries read into them.

Sign-in and roles also require the schema to hold users and roles. Someone who clones the repository and starts the
demo should be able to sign in immediately, without a manual setup step.

## Decision Drivers

- **Database-enforced invariants:** the open-alarm rule belongs in the schema, not in a column the code must keep in step.
- **Readable, reviewable schema history:** migrations should read as plain SQL.
- **Safe defaults:** default accounts with known passwords must never be usable outside demo mode, following the same
  rule as the demo SNMP passwords.

## Considered Options

1. **Stay on MySQL 8.4 with Flyway**, keeping the `open_key` column.
2. **PostgreSQL with Flyway.**
3. **PostgreSQL 18 with a Liquibase changelog of SQL-formatted changesets, and the demo accounts in a `demo` context** *(Chosen)*

## Decision Outcome

Chosen option: **Option 3.**

- **Schema:** the changelog [`db.changelog-master.yaml`] includes SQL changesets:
  - `001`: `event_log` (identity key, `timestamptz(3)` columns, `jsonb` payload) and `alarm`, with the partial unique
    index `uk_alarm_open` on `(equipment_id, code, instance) where state <> 'CLOSED'`. The `open_key` column is gone.
  - `002`: `role`, `app_user` and `user_role`, with the roles `VIEWER`, `SUPERVISOR` and `ADMIN`. Usernames must be
    lower case, so "Admin1" and "admin1" cannot become two accounts.
  - `003` (context `demo`): `admin1` (ADMIN), `supervisor1` (SUPERVISOR) and `demo1` (VIEWER). The passwords are
    stored only as `{bcrypt}` hashes in Spring Security's `DelegatingPasswordEncoder` format.
- **Demo context:** [`LiquibaseDemoContext`] sets the Liquibase context to `demo` when `atm.demo-mode` is true, and to
  `default` otherwise. The context is never empty, because Liquibase runs every changeset, the demo ones included,
  when no context is set. `application.yml` also sets `spring.liquibase.contexts: default`, so if the post-processor
  were ever missed, no demo accounts would be seeded: it fails closed.
- **Startup guard:** the demo changeset runs once, so a database first started in demo mode keeps its accounts.
  Outside demo mode, [`DemoAccountGuard`] therefore refuses to start while any demo account is still enabled with its
  default password. It runs at every start, like the check of the demo SNMP passwords.
- **No analytics:** `spring.liquibase.analytics-enabled: false`. By default Liquibase 5 fetches a remote analytics
  configuration on every run and may send usage data, which a supervision tool should not do.
- **Rule:** a changeset is never edited once committed. Schema changes come as new changesets.

### Consequences

- **Good:** the database itself guarantees one open alarm per identity, and the application no longer maintains a key column.
- **Good:** a fresh clone starts with roles and, in demo mode, with ready-to-use accounts. A fresh database outside demo
  mode gets the roles but no accounts. A database once seeded in demo mode refuses to start outside demo mode until the
  demo accounts are disabled or their passwords changed.
- **Good:** `jsonb` payloads can be queried directly, for example `payload->>'via'` in [`SupervisorEndToEndIT`].
- **Trade-off:** `jsonb` normalises key order and whitespace, so tests compare parsed JSON, never payload text.
- **Licence note:** Liquibase 5.x is released under the Functional Source License (FSL-1.1-ALv2). It is
  source-available, and each release becomes Apache-2.0 two years after publication. The licence restricts offering a
  product that competes with Liquibase. Using it as this project's migration tool is permitted, and ATM Supervisor
  itself stays Apache-2.0. Flyway (Apache-2.0) remains the fallback if a purely OSI-licensed toolchain is ever required.
- **Not migrated:** existing MySQL demo data is not migrated. The demo starts from an empty database.

## Verification

- [`JpaBoardStoreIT`]: `openAlarmIdentityIsUnique` and `closedAlarmFreesItsIdentity` exercise `uk_alarm_open`;
  `instantsAreStoredAsUtcMilliseconds` checks the `timestamptz(3)` precision.
- [`DemoSeedIT`]: the roles, the three demo accounts with one role each, that each documented password matches its
  stored hash, and that upper-case usernames are rejected.
- [`NoDemoSeedIT`]: outside demo mode the roles exist and there are no accounts.
- [`LiquibaseDemoContextTest`]: the context is `demo` in demo mode and `default` otherwise, never empty.
- [`LiquibaseDefaultsIT`]: without the post-processor, demo mode seeds no accounts, and Liquibase analytics are off.
- [`DemoSeedIT`]: `outsideDemoModeTheDefaultDemoAccountsRefuseStartup` and
  `disabledOrChangedDemoAccountsNoLongerBlockStartup` cover [`DemoAccountGuard`].
- [`SupervisorEndToEndIT`]: `demoModeSeedsTheDemoAccounts` proves that the real application selects the demo context.

[`db.changelog-master.yaml`]: ../../supervisor/src/main/resources/db/changelog/db.changelog-master.yaml
[`LiquibaseDemoContext`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/LiquibaseDemoContext.java
[`SupervisorEndToEndIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorEndToEndIT.java
[`JpaBoardStoreIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStoreIT.java
[`DemoSeedIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/DemoSeedIT.java
[`NoDemoSeedIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/NoDemoSeedIT.java
[`LiquibaseDemoContextTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/LiquibaseDemoContextTest.java
[`LiquibaseDefaultsIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/LiquibaseDefaultsIT.java
[`DemoAccountGuard`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/security/DemoAccountGuard.java
