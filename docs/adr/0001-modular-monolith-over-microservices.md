# ADR-0001: Modular Monolith Supervisor Plus a Separate Multi-Agent Simulator

- **Status:** Accepted (implemented)
- **Date:** 2026-11-13
- **Deciders:** Project maintainers

## Context and Problem Statement

ATM Supervisor polls CNS/ATM equipment over SNMP, receives asynchronous traps and informs, reconciles active device
alarm tables, derives the status of operational services (`SURV`, `FD`, and planned `VOICE`, `REC`, `NET`), records an
append-only event log, and streams state updates to an operator board in the browser.

We needed an application layout that:

1. Commits equipment observations, derived service states, alarm transitions, and the event log in one database
   transaction.
2. Keeps SNMP wire details (`snmp4j`) out of the domain rules (`board`, `alarms`, `impact`).
3. Runs self-contained and offline on a single machine under 3 GB RAM (`docker compose up -d --wait`).

## Decision Drivers

- **One transaction per state change:** a derived `SERVICE_STATE_CHANGED` event and supervisor alarms `903` (*Service
  redundancy lost*) or `904` (*Service failed*) must commit in the same transaction as the equipment state change that
  caused them.
- **Deterministic ordering:** concurrent polls, probes, traps, and operator actions must be ordered in one place
  without clock skew or races between services.
- **Explainability:** every module and process boundary should earn its keep rather than exist for infrastructure
  ceremony.

## Considered Options

1. **Modular monolith supervisor (`supervisor`) and a separate multi-agent simulator (`simulator`), sharing `mib` and
   `inventory` Maven modules** (chosen).
2. **Broker-centred microservices** (collector service -> Kafka or RabbitMQ -> state, alarm, history/replay and
   control services).
3. **Assembling off-the-shelf tools** (`snmpsim`, `snmp_exporter`, Prometheus, Alertmanager and Grafana).

## Decision Outcome

Chosen option: **Option 1.** Two Spring Boot 4.1 applications on Java 25 (`simulator` and `supervisor`), with
`supervisor` structured as a Spring Modulith modular monolith.

### Module boundaries in `supervisor`

- **`snmp`** ([`SnmpClient`], [`ObservationMapper`], [`TrapReceiver`]): speaks SNMPv3 over UDP and turns wire PDUs
  into typed [`Observation`], [`ProbeObservation`], and [`Notification`] records.
- **`polling`** ([`PollScheduler`], [`PollDispatcher`], [`DeviceBreakers`], [`TrapForwarder`]): schedules lightweight
  probes and circuit-breaker-guarded full polls, keeps full polls single-flight per device, and submits inputs to the
  board.
- **`alarms`** ([`AlarmEngine`], [`AlarmCorrelator`], [`AlarmCatalogue`]): pure alarm lifecycle (`ACTIVE_UNACK`,
  `ACTIVE_ACK`, `CLEARED_UNACK`, `CLOSED`), escalation, maintenance suppression, full-table reconciliation
  (`reconcile`), and topological correlation.
- **`board`** ([`BoardWriter`], [`BoardDecider`], [`StatusDeriver`], [`ServiceEvaluator`], [`BoardReducer`]): owns the
  live state and the single writer thread (`decide -> commit -> apply -> publish`).
- **`impact`** ([`ImpactAnalyzer`]): answers planned-maintenance and N-1 single-point-of-failure questions by rerunning
  [`ServiceEvaluator`].
- **`control`** ([`EquipmentControl`]): records command intent, sends zero-retry SNMP `SET` commands as the `operator`
  user, and records the outcome.
- **`history`** ([`JpaBoardStore`], [`EventCodec`], [`EventQueries`], [`BoardHistory`], [`JpaSnapshotStore`],
  [`IncidentSummary`]): commits `event_log` and `alarm` rows in one PostgreSQL transaction, stores snapshots, folds the
  board at start-up and for historical replay, and builds incident exports.
- **`security`** ([`SecurityConfig`], [`LiveSessions`]): session sign-in, CSRF, role checks and user administration.
- **`simconsole`** ([`SimulatorConsoleController`]): forwards the simulator fault console in demo mode.
- **`api`** ([`BoardController`], [`AlarmController`], [`ImpactController`], [`CommandController`],
  [`StreamController`], [`ReplayController`], [`SseBroadcaster`]): REST endpoints and bounded Server-Sent Events
  (`/api/v1/stream`).

Module boundaries are checked in the build by [`ModularityTest`]
(`ApplicationModules.of(SupervisorApplication.class).verify()`).

### Consequences

- **Good:** a single [`BoardWriter`] thread processes all inputs in order and commits equipment events, derived
  service events, and alarm rows in one PostgreSQL transaction through [`JpaBoardStore`].
- **Good:** no outbox relay is needed because `event_log` commits in the same transaction as the state change, and
  [`SseBroadcaster`] publishes only committed [`StoredEvent`] records.
- **Good:** if a module ever moves behind a message broker later, `event_log` already holds self-contained full-state
  JSON payloads.
- **Trade-off:** throughput is bounded to one JVM process, which is plenty for the 15-device centre at a 5 s poll
  interval.

## Verification

- [`ModularityTest`]: checks that the Spring Modulith module boundaries have no illegal dependencies or cycles.
- [`SupervisorEndToEndIT`]: runs the supervisor against in-process UDP simulator agents and PostgreSQL 18 in
  Testcontainers.

[`AlarmCatalogue`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmCatalogue.java
[`AlarmController`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/AlarmController.java
[`AlarmCorrelator`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmCorrelator.java
[`AlarmEngine`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[`BoardController`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/BoardController.java
[`BoardDecider`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[`BoardHistory`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/BoardHistory.java
[`BoardReducer`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducer.java
[`BoardWriter`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`CommandController`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/CommandController.java
[`DeviceBreakers`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/DeviceBreakers.java
[`EquipmentControl`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/control/EquipmentControl.java
[`EventCodec`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/EventCodec.java
[`EventQueries`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/EventQueries.java
[`ImpactAnalyzer`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/impact/ImpactAnalyzer.java
[`ImpactController`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/ImpactController.java
[`IncidentSummary`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/IncidentSummary.java
[`JpaBoardStore`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[`JpaSnapshotStore`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaSnapshotStore.java
[`LiveSessions`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/security/LiveSessions.java
[`ModularityTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/ModularityTest.java
[`Notification`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/Notification.java
[`ObservationMapper`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/ObservationMapper.java
[`Observation`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/Observation.java
[`PollDispatcher`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollDispatcher.java
[`PollScheduler`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[`ProbeObservation`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/ProbeObservation.java
[`ReplayController`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/ReplayController.java
[`SecurityConfig`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/security/SecurityConfig.java
[`ServiceEvaluator`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluator.java
[`SimulatorConsoleController`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/simconsole/SimulatorConsoleController.java
[`SnmpClient`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/SnmpClient.java
[`SseBroadcaster`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseBroadcaster.java
[`StatusDeriver`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StatusDeriver.java
[`StoredEvent`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StoredEvent.java
[`StreamController`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/StreamController.java
[`SupervisorEndToEndIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorEndToEndIT.java
[`TrapForwarder`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/TrapForwarder.java
[`TrapReceiver`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/TrapReceiver.java
