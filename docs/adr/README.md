# Architecture Decision Records (ADRs)

This directory records the significant architectural decisions of **ATM Supervisor**, following the [Michael Nygard](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions) / [MADR](https://adr.github.io/madr/) format. Each record states the problem context, considered alternatives, the chosen decision, its trade-offs, and the concrete code and tests that enforce it in the current release (`v0.1.1`) as well as what is planned for later phases (`v0.2`–`v1.0`).

| ADR | Title | Status | Scope |
|---|---|---|---|
| [ADR-0001](0001-modular-monolith-over-microservices.md) | Modular monolith supervisor plus a separate multi-agent simulator | Accepted | Implemented (`v0.1.1`) |
| [ADR-0002](0002-snmpv3-authpriv-and-legacy-v2c.md) | SNMPv3 `authPriv` by default, persistent `snmpEngineBoots`, and SNMPv2c (`IF-MIB`) for legacy switches | Accepted | v3 implemented (`v0.1.1`); v2c switches planned (`v0.2`) |
| [ADR-0003](0003-traps-as-hints-polling-as-source-of-truth.md) | Traps as low-latency hints, polling as source of truth, and alarm-table reconciliation on every full poll | Accepted | Implemented (`v0.1.1`) |
| [ADR-0004](0004-full-state-event-log-and-upsert-reducer.md) | Full-state event log, pure upsert reducer, and 30-second sampled telemetry without an outbox | Accepted | Event log & reducer implemented (`v0.1.1`); replay UI & snapshots planned (`v0.3`) |
| [ADR-0005](0005-sse-with-bounded-queues-over-websocket.md) | Server-Sent Events (SSE) with bounded per-client queues and ring-buffer resume over WebSockets | Accepted | Implemented (`v0.1.1`) |
| [ADR-0006](0006-single-shared-inventory.md) | Single shared device inventory (`config/inventory.yml`) across simulator and supervisor | Accepted | Implemented (`v0.1.1`) |
| [ADR-0007](0007-supervisor-ha-as-future-work.md) | Single supervisor instance with state-preserving restart; active/standby supervisor HA left as future work | Accepted | Implemented (`v0.1.1`) |
| [ADR-0008](0008-independent-probes-two-generations-and-crash-safe-commands.md) | Independent health probes, two-generation single-flight polling, and crash-safe `UNCERTAIN` commands | Accepted | Probes, breakers & two generations implemented (`v0.1.1`); supervisor commands planned (`v0.3`) |
| [ADR-0009](0009-postgresql-and-liquibase.md) | PostgreSQL 18 with a Liquibase changelog and demo-only seed accounts | Accepted | Implemented (after `v0.1.1`) |
| [ADR-0010](0010-session-sign-in-and-roles.md) | Session sign-in with CSRF protection and a three-role hierarchy | Accepted | Implemented (after `v0.1.1`) |
| [ADR-0011](0011-control-and-simulator-through-the-supervisor.md) | Equipment control, the simulator console and account administration go through the supervisor | Accepted | Implemented (after `v0.1.1`) |
