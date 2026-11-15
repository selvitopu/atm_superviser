# ADR-0001: Modular Monolith Supervisor Plus a Separate Multi-Agent Simulator

* **Status:** Accepted — Implemented in `v0.1.1`
* **Date:** 2026-11-13 (revised 2026-11-15 for `v0.1.1`)
* **Deciders:** Project maintainers

## Context and Problem Statement

ATM Supervisor polls CNS/ATM equipment over SNMP, receives asynchronous traps and informs, reconciles active device alarm tables, derives the status of operational services (`SURV`, `FD`, and planned `VOICE`, `REC`, `NET`), persists an append-only event log, and streams real-time state updates to an operator browser board.

We needed to choose an application topology that:
1. Guarantees atomic consistency between equipment observations, derived service states, alarm transitions, and the persisted event log.
2. Enforces strict module boundaries so SNMP wire details (`snmp4j`) do not leak into domain rules (`board`, `alarms`).
3. Runs self-contained and offline on a single developer machine under 3 GB RAM (`docker compose up -d --wait`).

## Decision Drivers

* **Cross-domain transactional invariants:** A derived `SERVICE_STATE_CHANGED` event and supervisor alarms `903` (*Service redundancy lost*) or `904` (*Service failed*) must commit in the same database transaction as the equipment state change that caused them.
* **Deterministic ordering:** Concurrent polls, probes, traps, and operator acknowledgements must be ordered deterministically without distributed clock skew or race conditions.
* **Explainability:** Every component and boundary should exist to solve a real supervision problem rather than adding infrastructure ceremony.

## Considered Options

1. **Modular monolith supervisor (`supervisor`) + separate multi-agent simulator (`simulator`) sharing `mib` and `inventory` Maven modules** *(Chosen)*
2. **Broker-centred microservices** (collector service $\rightarrow$ Kafka/RabbitMQ $\rightarrow$ state service, alarm service, history/replay service, control service)
3. **Assembling off-the-shelf tools** (`snmpsim` + `snmp_exporter` + Prometheus + Alertmanager + Grafana)

## Decision Outcome

Chosen option: **Option 1 — Two Spring Boot 4.1 applications on Java 25 (`simulator` and `supervisor`), with `supervisor` structured as a Spring Modulith modular monolith.**

### Module Boundaries in `supervisor`

* **`snmp`** ([`SnmpClient`], [`ObservationMapper`], [`TrapReceiver`]): Speaks SNMPv3 over UDP and translates wire PDUs into typed [`Observation`], [`ProbeObservation`], and [`Notification`] records.
* **`polling`** ([`PollScheduler`], [`PollDispatcher`], [`DeviceBreakers`], [`TrapForwarder`]): Schedules lightweight probes and circuit-breaker-guarded full polls, keeps full polls single-flight per device, and submits inputs to the board.
* **`alarms`** ([`AlarmEngine`], [`AlarmCatalogue`]): Pure alarm lifecycle (`ACTIVE_UNACK`, `ACTIVE_ACK`, `CLEARED_UNACK`, `CLOSED`), escalation, maintenance suppression, and full-table reconciliation (`reconcile`).
* **`board`** ([`BoardWriter`], [`BoardDecider`], [`StatusDeriver`], [`ServiceEvaluator`], [`BoardReducer`]): Owns the live state and the single writer thread (`derive → commit → apply → publish`).
* **`history`** ([`JpaBoardStore`], [`EventCodec`], [`EventQueries`]): Persists `event_log` and `alarm` rows in one MySQL transaction and reloads open alarms and last-known equipment states on restart.
* **`api`** ([`BoardController`], [`AlarmController`], [`StreamController`], [`SseBroadcaster`]): Exposes REST endpoints and bounded Server-Sent Events (`/api/v1/stream`).

Module boundaries are verified automatically in CI by [`ModularityTest`] (`ApplicationModules.of(SupervisorApplication.class).verify()`).

### Consequences

* **Good:** A single [`BoardWriter`] thread processes all inputs sequentially and commits equipment events, derived service events, and alarm rows in one MySQL transaction through [`JpaBoardStore`].
* **Good:** No outbox relay is needed because `event_log` is committed in the same transaction as the state change, and [`SseBroadcaster`] publishes only committed [`StoredEvent`] records.
* **Good:** If a module ever needs to be extracted behind a message broker in the future, `event_log` already contains self-contained full-state JSON payloads.
* **Bad / Trade-off:** Throughput is bounded to a single JVM process (which easily handles the planned 15 devices at a 5 s poll interval, a few inputs per second).

## Verification

* [`ModularityTest`] — Verifies that Spring Modulith module boundaries have no illegal dependencies or cycles.
* [`SupervisorEndToEndIT`] — Exercises the full modular monolith against in-process UDP simulator agents and MySQL 8.4 in Testcontainers.

[SnmpClient]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/SnmpClient.java
[ObservationMapper]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/ObservationMapper.java
[TrapReceiver]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/TrapReceiver.java
[Observation]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/Observation.java
[ProbeObservation]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/ProbeObservation.java
[Notification]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/Notification.java
[PollScheduler]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[PollDispatcher]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollDispatcher.java
[DeviceBreakers]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/DeviceBreakers.java
[TrapForwarder]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/TrapForwarder.java
[AlarmEngine]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[AlarmCatalogue]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmCatalogue.java
[BoardWriter]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[BoardDecider]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[StatusDeriver]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StatusDeriver.java
[ServiceEvaluator]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluator.java
[BoardReducer]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducer.java
[StoredEvent]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StoredEvent.java
[JpaBoardStore]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[EventCodec]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/EventCodec.java
[EventQueries]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/EventQueries.java
[BoardController]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/BoardController.java
[AlarmController]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/AlarmController.java
[StreamController]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/StreamController.java
[SseBroadcaster]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseBroadcaster.java
[ModularityTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/ModularityTest.java
[SupervisorEndToEndIT]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorEndToEndIT.java
[`AlarmCatalogue`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmCatalogue.java
[`AlarmController`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/AlarmController.java
[`AlarmEngine`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[`BoardController`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/BoardController.java
[`BoardDecider`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[`BoardReducer`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducer.java
[`BoardWriter`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`DeviceBreakers`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/DeviceBreakers.java
[`EventCodec`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/EventCodec.java
[`EventQueries`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/EventQueries.java
[`JpaBoardStore`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[`ModularityTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/ModularityTest.java
[`Notification`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/Notification.java
[`ObservationMapper`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/ObservationMapper.java
[`Observation`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/Observation.java
[`PollDispatcher`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollDispatcher.java
[`PollScheduler`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[`ProbeObservation`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/ProbeObservation.java
[`ServiceEvaluator`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluator.java
[`SnmpClient`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/SnmpClient.java
[`SseBroadcaster`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseBroadcaster.java
[`StatusDeriver`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StatusDeriver.java
[`StoredEvent`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StoredEvent.java
[`StreamController`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/StreamController.java
[`SupervisorEndToEndIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorEndToEndIT.java
[`TrapForwarder`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/TrapForwarder.java
[`TrapReceiver`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/TrapReceiver.java
