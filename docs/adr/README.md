# Architecture Decision Records (ADRs)

This directory records the architectural decisions of ATM Supervisor. Each record states the problem, the options
considered, the decision and its trade-offs, and the classes and tests that verify it.

| ADR | Title | Status | Scope |
|---|---|---|---|
| [ADR-0001](0001-modular-monolith-over-microservices.md) | Modular monolith supervisor plus a separate multi-agent simulator | Accepted | Implemented |
| [ADR-0002](0002-snmpv3-authpriv-and-legacy-v2c.md) | SNMPv3 `authPriv` by default, persistent `snmpEngineBoots`, and SNMPv2c (`IF-MIB`) for legacy switches | Accepted | v3 implemented; v2c switches planned (`v0.2`) |
| [ADR-0003](0003-traps-as-hints-polling-as-source-of-truth.md) | Traps as low-latency hints, polling as source of truth, and alarm-table reconciliation on every full poll | Accepted | Implemented |
| [ADR-0004](0004-full-state-event-log-and-upsert-reducer.md) | Full-state event log, pure upsert reducer, and 30-second sampled telemetry | Accepted | Implemented (see also [ADR-0014](0014-replay-from-snapshots-and-the-event-log.md)) |
| [ADR-0005](0005-sse-with-bounded-queues-over-websocket.md) | Server-Sent Events (SSE) with bounded per-client queues and ring-buffer resume over WebSockets | Accepted | Implemented |
| [ADR-0006](0006-single-shared-inventory.md) | Single shared device inventory (`config/inventory.yml`) across simulator and supervisor | Accepted | Implemented |
| [ADR-0007](0007-supervisor-ha-as-future-work.md) | Single supervisor instance with state-preserving restart; active/standby supervisor HA left as future work | Accepted | Implemented |
| [ADR-0008](0008-independent-probes-two-generations-and-crash-safe-commands.md) | Independent health probes, two-generation single-flight polling, confirmed service facts, and crash-safe commands | Accepted | Probes, breakers, two generations and intent-first commands implemented; `Idempotency-Key` and `active_command` exclusion planned (`v0.3`) |
| [ADR-0009](0009-postgresql-and-liquibase.md) | PostgreSQL 18 with a Liquibase changelog and demo-only seed accounts | Accepted | Implemented |
| [ADR-0010](0010-session-sign-in-and-roles.md) | Session sign-in with CSRF protection and a three-role hierarchy | Accepted | Implemented |
| [ADR-0011](0011-control-and-simulator-through-the-supervisor.md) | Equipment control, the simulator console and account administration go through the supervisor | Accepted | Implemented |
| [ADR-0012](0012-impact-analysis-reruns-the-live-service-rules.md) | Impact analysis reruns the live service rules | Accepted | Implemented |
| [ADR-0013](0013-alarm-correlation-recorded-from-topology-rules.md) | Alarm correlation is recorded when it is made, from fixed topology rules | Accepted | Implemented |
| [ADR-0014](0014-replay-from-snapshots-and-the-event-log.md) | Replay rebuilds the board from snapshots and the event log; playback runs in the browser | Accepted | Implemented |
