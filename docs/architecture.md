# System Architecture

This document describes the architecture of **ATM Supervisor** as implemented in **v0.1.1**, together with how the planned phases (`v0.2`–`v1.0`) fit into the same module boundaries. For the full target specification see the [design document](design/2026-11-13-atm-supervisor-design.md) and the [architecture review decisions](refactor/ATM-Supervisor-Architecture-Review-Decisions.md).

---

## 1. C4 Architecture Views

### 1.1 Level 1 — System Context

```mermaid
flowchart LR
    SC["System Controller<br/>(Technical Supervision Position)"]
    SUP["ATM Supervisor<br/>(Supervision & Alarm Engine)"]
    SIM["Demo ACC Simulated Estate<br/>(SNMPv3 Equipment Agents)"]

    SC -->|"Views live board & service state, acknowledges alarms,<br/>sends equipment commands, injects demo faults<br/>(HTTP / SSE :8080)"| SUP
    SUP -->|"SNMPv3 authPriv GET / GETBULK / SET<br/>(probes, full polls & commands, UDP 16101–16107)"| SIM
    SUP -.->|"Forwards the simulator console<br/>(HTTP :8090, demo mode only)"| SIM
    SIM -->|"SNMPv3 TRAPs & INFORMs<br/>(UDP 10162)"| SUP
```

### 1.2 Level 2 — Container Diagram (`deploy/docker-compose.yml`)

```mermaid
flowchart LR
    subgraph Browser["Browser"]
        SPA["Angular 22 Operator Board<br/>(Zoneless Signals)"]
    end

    subgraph Compose["Docker Compose Stack (atm-supervisor)"]
        UI["ui (nginx :8080)<br/>Serves SPA, proxies /api -> supervisor:8080"]
        SUP["supervisor (Spring Boot 4.1, Java 25)<br/>REST & SSE :8080 · Actuator :8081<br/>Trap Receiver UDP :10162"]
        SIM["simulator (Spring Boot 4.1, Java 25)<br/>7 SNMP4J Core Agents UDP :16101–16107<br/>Fault Injection API :8090"]
        DB[("postgres (PostgreSQL 18)<br/>event_log, alarm & user tables")]
        VOL[("simulator-data volume<br/>engine-boots.json")]
    end

    SPA -->|"HTTP + SSE (/api/v1/stream)"| UI
    UI -->|"/api/v1/*"| SUP
    SUP -->|"SNMPv3 authPriv<br/>(SHA-256 / AES-128)"| SIM
    SUP -.->|"/api/* console<br/>(demo mode only)"| SIM
    SIM -->|"TRAP / INFORM<br/>(UDP 10162)"| SUP
    SUP -->|"JDBC / Liquibase"| DB
    SIM --> VOL
```

### 1.3 Level 3 — Supervisor Component & Module View

Inside `supervisor/`, Spring Modulith enforces six acyclic modules ([`ModularityTest`]):

```mermaid
flowchart TB
    subgraph snmp["snmp module"]
        SESS["SnmpSession & Snmp4jClient"]
        OMAP["ObservationMapper<br/>(scalars + sensor & alarm tables)"]
        TREC["TrapReceiver & NotificationMapper<br/>(UDP :10162 TRAP & INFORM)"]
    end

    subgraph polling["polling module"]
        PSCHED["PollScheduler<br/>(Single-Flight Full Polls +<br/>Unbreakered Probes)"]
        PDISP["PollDispatcher<br/>(Bounded Virtual-Thread Executor)"]
        DBRK["DeviceBreakers<br/>(Per-Device Resilience4j Breaker)"]
        TFWD["TrapForwarder"]
    end

    subgraph board["board module (Single-Writer Core)"]
        BWR["BoardWriter<br/>(Single-Threaded Loop:<br/>decide -> commit -> apply -> publish)"]
        BDEC["BoardDecider<br/>(Pure Decision & Two-Generation Guard)"]
        SDER["StatusDeriver<br/>(Health, Contact, UnknownReason)"]
        SEVAL["ServiceEvaluator<br/>(SurveillanceRule & FlightDataRule<br/>Optimistic / Pessimistic Passes)"]
        BRED["BoardReducer<br/>(Pure Entity-Upsert Fold)"]
    end

    subgraph alarms["alarms module"]
        AENG["AlarmEngine<br/>(Lifecycle, Escalation, Suppression,<br/>Full-Table Resync)"]
        ACAT["AlarmCatalogue"]
    end

    subgraph history["history module"]
        JSTORE["JpaBoardStore & EventCodec"]
        EQUER["EventQueries"]
        BHIST["BoardHistory, GapRule,<br/>JpaSnapshotStore, IncidentSummary"]
    end

    subgraph api["api module"]
        CTRL["BoardController, AlarmController,<br/>StreamController, ReplayController"]
        SSEB["SseBroadcaster & SseClient<br/>(1000-Event Ring Buffer +<br/>Bounded 256-Event Client Queues)"]
    end

    PSCHED --> PDISP
    PSCHED --> DBRK
    PDISP --> SESS
    SESS --> OMAP
    TREC --> TFWD
    PSCHED -->|"ProbeSucceeded / ProbeFailed<br/>PollSucceeded / PollFailed"| BWR
    TFWD -->|"TrapReceived"| BWR
    CTRL -->|"AcknowledgeAlarms"| BWR
    BWR --> BDEC
    BDEC --> SDER
    BDEC --> SEVAL
    BDEC --> AENG
    AENG --> ACAT
    BWR -->|"1. Commit in one Tx"| JSTORE
    CTRL -->|"board at T, events, export"| BHIST
    BWR -->|"2. Fold committed events"| BRED
    BWR -->|"3. Non-blocking publish"| SSEB
    BWR -.->|"RepollRequested<br/>(TRAP / CONTACT_RESTORED)"| PSCHED
```

---

## 2. Core Runtime Pipelines

### 2.1 Probes, Full Polls, Circuit Breakers, and the Two Generations

To prevent the circuit breaker from masking contact loss or delaying recovery ([ADR-0008](adr/0008-independent-probes-two-generations-and-crash-safe-commands.md), [Hazard H1](safety/hazard-log.md#h1-stale-observations-shown-as-current), [Hazard H3](safety/hazard-log.md#h3-circuit-breaker-state-masks-contact-loss)):

1. **Lightweight Probes (`sysUpTime.0`):**
   * Scheduled by [`PollScheduler`] at the device's poll interval (`5 s ± 10 %` jitter) and executed on [`PollDispatcher`] **without** passing through [`DeviceBreakers`].
   * Probes are the sole authority for `reachable`, `consecutiveFailures`, `UNKNOWN (LOST_CONTACT)` (after 3 failed probes), and alarm `901`.
   * Probes carry the device's **`contactGeneration`** (`LiveBoard.contactGeneration(id)`), which is bumped **only** when contact changes (`CONTACT_LOST` or `CONTACT_RESTORED`). Because traps do not bump `contactGeneration`, a trap flood cannot discard failed probes and mask a lost contact.
2. **Full Polls (`GET` scalars + `GETBULK` tables):**
   * Run through the per-device Resilience4j circuit breaker in [`DeviceBreakers`] (count-based window of 5 calls, minimum 3 calls, 50 % failure threshold, 30 s wait in `OPEN`, 1 permitted call in `HALF_OPEN`, no automatic time-based `OPEN → HALF_OPEN`).
   * At most **one** full poll runs per device at a time (single-flight). Traps arriving during an active poll set a pending refresh flag so exactly one follow-up poll runs when the active poll completes.
   * Every full poll walks `atmTsAlarmTable` (`AtmTsOids.ALARM_ENTRY`) in addition to the device's scalars and type-specific tables ([`PollPlan`]). If the alarm walk fails midway or a row is malformed, [`ObservationMapper`] fails the entire observation (`PollFailed`) so a partial walk never clears alarms.
   * Full polls carry the device's **`statusGeneration`** (`LiveBoard.generation(id)`), which is bumped by traps and by contact changes.
3. **Immediate `HALF_OPEN` Verification When Contact Returns:**
   * When a probe succeeds for a device in `LOST_CONTACT`, [`BoardDecider`] emits `CONTACT_RESTORED` (setting health to `UNKNOWN (AWAITING_STATUS)`) and requests `RepollRequested(id, Reason.CONTACT_RESTORED)`.
   * [`PollScheduler.onRepoll`] moves an `OPEN` breaker for that device to `HALF_OPEN` and schedules an immediate full poll. If that verification poll succeeds, the breaker closes and health becomes `OPERATIONAL`/`DEGRADED`/`FAILED`; if it fails, the breaker reopens and the device stays `UNKNOWN`.
   * If probes continue to succeed for a device whose health was known, but no full poll succeeds for more than **3× the poll interval**, [`StatusDeriver`] sets health to `UNKNOWN (STATUS_UNAVAILABLE)`.

### 2.2 The Single-Writer Loop (`BoardWriter`)

All board inputs (`ProbeSucceeded`, `ProbeFailed`, `PollSucceeded`, `PollFailed`, `TrapReceived`, `AcknowledgeAlarms`, `Startup`, `HeartbeatTick`, `Shutdown`) are processed sequentially on the single [`BoardWriter`] thread:

```mermaid
sequenceDiagram
    participant In as PollScheduler / TrapForwarder / API
    participant BW as BoardWriter (Single Thread)
    participant BD as BoardDecider (Pure)
    participant DB as JpaBoardStore (PostgreSQL Tx)
    participant BR as BoardReducer (Pure)
    participant SSE as SseBroadcaster

    In->>BW: submit(BoardInput)
    BW->>BD: decide(liveBoard, input, now)
    BD-->>BW: Decision(events, telemetry, repoll, invalidated, contactInvalidated)
    BW->>DB: commit(events)
    alt Commit succeeds
        DB-->>BW: List<StoredEvent> (with assigned event_log.id)
        BW->>BR: apply(board, event) for each committed event
        BW->>BW: Update LiveBoard & bump invalidated / contactInvalidated generations
        BW->>SSE: publish(storedEvents, telemetry)
        BW->>In: publish(RepollRequested)
    else Commit fails
        BW->>BW: Keep previous Board, bump the invalidated generations (the next input retries)
        BW->>In: publish(RepollRequested)
    end
```

At start-up the writer does not load the alarm table and the last equipment events. It asks `BoardStore.foldedBoard()` for the board, which [`BoardHistory`] folds from the newest snapshot plus the later events (or from the whole log when there is no snapshot), and then commits `SUPERVISOR_STARTED` on top. After applying a commit it also takes a board snapshot (below), at start-up and then at most once per `atm.history.snapshot-interval`, outside the commit's transaction.

### 2.3 Two-Pass Service Evaluation & Confirmed Facts (`ServiceEvaluator`)

[`ServiceEvaluator`] runs every [`ServiceRule`] ([`SurveillanceRule`], [`FlightDataRule`]) twice:
1. **Optimistic pass (`best`):** Every `UNKNOWN` device is treated as available (`OPERATIONAL`).
2. **Pessimistic pass (`worst`):** Every `UNKNOWN` device is treated as unavailable (`FAILED`).

* **If `best.status() != worst.status()`:** The service state is `UNKNOWN` with `bestCase = best.status()`, and its reason names the `UNKNOWN` equipment the result depends on.
* **If `best.status() == worst.status()`:** That is the confirmed service status. Its `redundancyLost` flag and confirmed reasons come from `best`, while any extra reasons present only in `worst` are reported as a single `"Unconfirmed: … (equipment in UNKNOWN state: …)"` reason.
* **Alarm `903` follows confirmed facts only:** when the two passes disagree on `redundancyLost`, the service state is marked `redundancyUnconfirmed` and [`BoardDecider`] passes no redundancy fact to [`AlarmEngine`], so alarm `903` (*Service redundancy lost*) is neither raised nor cleared.

### 2.4 Bounded SSE Fan-Out (`SseBroadcaster` & `SseClient`)

* [`SseBroadcaster`] holds a **1,000-event ring buffer** of committed [`StoredEvent`] records.
* Each connected browser owns a [`SseClient`] with a bounded **256-entry durable queue** plus a latest-only coalescing map for volatile `telemetry` messages.
* If a slow browser fills its 256-entry durable queue, [`SseBroadcaster`] disconnects it (`reason="overflow"`) instead of blocking [`BoardWriter`] or dropping durable events. On reconnect, the browser resumes from `Last-Event-ID` or receives `event: resync` and refetches `GET /api/v1/board`.

### 2.5 Alarm Correlation and the Flood State (`AlarmCorrelator`)

Every alarm change passes through [`AlarmCorrelator`](../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmCorrelator.java) inside [`BoardDecider`], before it becomes an event ([ADR-0013](adr/0013-alarm-correlation-recorded-from-topology-rules.md)).

* **Recorded, not recomputed:** the alarm's `correlation` (cause id, cause equipment and code, and the rule: `SENSOR_INPUT`, `SWITCHOVER` or `SAME_UNIT`) is part of the `ALARM_RAISED` and `ALARM_UPDATED` payloads and the `alarm` table. The API and the UI only read it. A cause that arrives late adopts its alarms with `ALARM_UPDATED` events `via CORRELATION`, and a link that severity would break is removed the same way.
* **Flood state:** `Board.flood` (`active`, `since`) is durable and changes only through `ALARM_FLOOD_STARTED` and `ALARM_FLOOD_ENDED`. `SUPERVISOR_STARTED` clears it. The window of recent raise times lives in `LiveBoard` only, is stored after a successful commit, and starts empty after a restart. The stream carries a committed flood event as `flood`.
* **Configuration** (`supervisor` properties):

| Property | Default | Meaning |
|---|---|---|
| `atm.alarms.flood.window` | `10m` | The window over which new alarms are counted. |
| `atm.alarms.flood.start` | `10` | The flood starts when the window holds this many raises. |
| `atm.alarms.flood.end` | `5` | The flood ends when the window holds fewer than this many. |

The window must be positive, `start` at least 1, and `end` between 0 and `start`.

### 2.6 Replay and the Incident Export

Replay is a read path over the same event log. The server rebuilds the board at an instant, and the browser plays the later events forward.

**The board at T ([`BoardHistory`]).** Take the newest `board_snapshot` with `taken_at` at or before T (or `Board.initial` with no alarms when there is none), fold the events after the snapshot's `last_event_id` with `occurred_at` up to T using [`BoardReducer`], and add each unit's newest `TELEMETRY_SAMPLED` that is newer than the newest `SUPERVISOR_STARTED` at or before T. An instant in the future is answered as now. `noData` follows [`GapRule`]: no event other than a sample in `(T - threshold, T]`, or the newest such event is `SUPERVISOR_STOPPING`, or T is before the first event. The threshold is 1.5 x `atm.supervisor.heartbeat-interval`, 90 s at the default 60 s.

**Snapshots.** The `SnapshotStore` port lives in `board` and its implementation in `history`, so `board` gains no dependency on `history`. `board_snapshot` holds the durable board (equipment, services, open alarms, flood, last event id) as versioned JSON, never telemetry or generations. [`BoardWriter`] writes one at start-up and then at most once per `atm.history.snapshot-interval` (default `5m`). A failed write is logged and counted in `atm_snapshot_failures_total`, and never affects the live board. Nothing is purged.

**Endpoints.** All are read-only, under `/api/v1/history`, open to every signed-in role. Refusals are problem details (`400`; `404` for an unknown alarm).

| Method & Path | Description |
|---|---|
| `GET /board?at=<ISO instant>` | `ReplayBoardDto`: `at`, `noData`, `gapThresholdSeconds`, `lastRecordedAt`, `lastRecordedType` (the newest non-sample event at or before `at`) and the `board` in the live shape. Each equipment has the sample's `values`, `tables` and `sampledAt`; `contact.lastPollAt` is null and `consecutiveFailures` is 0. |
| `GET /events?after=<id>&until=<ISO>&limit=<1..1000, default 500>` | `{events, more}` in id order, `id > after` and `occurred_at <= until`. Each event has `id`, `occurredAt`, `type`, `equipmentId`, `actor`, `summary` and `message {kind, data}`. The kinds are the live stream's (`equipment`, `service`, `alarm`, `flood`, `supervisor`) plus the replay-only `sample` and `command`; [`StreamMessages`] maps both so the shapes cannot drift. |
| `GET /timeline?from&to` | `markers` (each `ALARM_RAISED`: time, unit, code, severity, text, suppressed; the newest 2,000, with `truncated: true` past that), `floods`, `gaps` and `restarts`. It reads with type filters and never scans samples. The window is at most 7 days. |
| `GET /alarms/{id}/raised` | `occurredAt` of the newest `ALARM_RAISED` event for that alarm id; `404` when there is none. The Alarm history **Replay** link resolves through it, because a resync raise stores the device's own `raisedAt`, which can be hours before the supervisor recorded it. |
| `GET /export?from&to&format=csv\|json` | An attachment named `incident-<yyyyMMdd'T'HHmm'Z'>-<...>.<csv\|json>`. The window is at most 7 days with `from < to`, otherwise `400`. JSON is `{centre, from, to, generatedAt, generatedBy, summary, events}`. CSV is the summary as `# key: value` lines, then the header `id,occurred_at,type,equipment,actor,summary` and one RFC 4180 row per event. Telemetry samples are left out. |

**The incident summary ([`IncidentSummary`]).** A pure function of the window, the board at `from` and at `to`, the events and the timeline. It reports alarms raised by severity with suppressed ones counted apart; causes (`DEVICE` 101) with their correlated counts; service degradations, where a period open at `from` takes its true start and counts its duration inside the window; commands paired by `commandId`, with an intent that has no outcome as `NOT_RECORDED`; floods, contact losses, gaps and restarts, where a `SUPERVISOR_STARTED` ends an open flood, contact loss or degradation (`endedByRestart`); and the alarms still open at `to`.

**The browser.** `/replay` has its own `ReplayStore`, which depends only on the history API client and never on the live stream or the live board store. It loads the board at the seek instant, prefetches event pages ahead of the play head, applies each event up to the virtual clock with the same upsert as the live board, and applies the gap rule with the server's threshold. On a `SUPERVISOR_STARTED` it reloads the board at that instant. The page shows no live header, no acknowledgement and no equipment control.

---

## 3. Persistence Schema (Liquibase changelog)

The schema is created by the Liquibase changelog [`db.changelog-master.yaml`](../supervisor/src/main/resources/db/changelog/db.changelog-master.yaml), whose SQL changesets live in [`changes/`](../supervisor/src/main/resources/db/changelog/changes). Changeset `003` loads the demo accounts only in demo mode ([ADR-0009](adr/0009-postgresql-and-liquibase.md)), `004` adds the alarm correlation columns, and `005` adds the replay snapshots.

| Table | Purpose | Key Columns & Constraints |
|---|---|---|
| `event_log` | Append-only log of all equipment, service, alarm, supervisor lifecycle, and 30 s `TELEMETRY_SAMPLED` events. | `id bigint generated always as identity`, `occurred_at` and `recorded_at` (`timestamptz(3)`), `type`, `entity_kind`, `entity_id`, `equipment_id`, `actor`, `payload jsonb` (full new entity state + `schemaVersion`; alarm events also record `via` and their `correlation`; `ALARM_FLOOD_STARTED` and `ALARM_FLOOD_ENDED` have entity kind `FLOOD`). Indexed on `(occurred_at)`, `(equipment_id, occurred_at)` and `(type, occurred_at)`. |
| `alarm` | Open and closed alarm records. | `id`, `equipment_id`, `code`, `instance`, `instance_name`, `severity`, `text`, `source`, `state`, `suppressed`, `raised_at`, `acked_at`, `acked_by`, `ack_comment`, `cleared_at`, and the correlation: `cause_id`, `cause_equipment_id`, `cause_code` and `correlation_rule`, all null or all set, with no foreign key because retention may purge a cause. The partial unique index `uk_alarm_open` on `(equipment_id, code, instance) where state <> 'CLOSED'` allows one open alarm per identity. |
| `role`, `app_user`, `user_role` | Roles (`VIEWER`, `SUPERVISOR`, `ADMIN`) and accounts. | Usernames are unique and lower case; passwords are stored as `{bcrypt}` hashes. In demo mode, `admin1`, `supervisor1` and `demo1` are created with one role each. |
| `board_snapshot` | The durable board at a point in the log, so replay folds only the events after it. | `id bigint generated always as identity`, `taken_at timestamptz(3)`, `last_event_id`, `schema_version`, `board jsonb`. Indexed on `(taken_at)`. A snapshot equals the fold of every event up to `last_event_id`. |

*(Planned for `v0.3`: `command` and `active_command(equipment_id PRIMARY KEY)` for crash-safe command exclusion.)*

[ModularityTest]: ../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/ModularityTest.java
[PollScheduler]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[PollScheduler.onRepoll]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[PollDispatcher]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollDispatcher.java
[DeviceBreakers]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/DeviceBreakers.java
[PollPlan]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/PollPlan.java
[ObservationMapper]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/ObservationMapper.java
[BoardWriter]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[BoardDecider]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[StatusDeriver]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StatusDeriver.java
[ServiceEvaluator]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluator.java
[ServiceRule]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceRule.java
[SurveillanceRule]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/SurveillanceRule.java
[FlightDataRule]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/FlightDataRule.java
[AlarmEngine]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[StoredEvent]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StoredEvent.java
[SseBroadcaster]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseBroadcaster.java
[SseClient]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseClient.java
[`AlarmEngine`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[`BoardDecider`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[`BoardHistory`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/BoardHistory.java
[`GapRule`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/GapRule.java
[`IncidentSummary`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/IncidentSummary.java
[`StreamMessages`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/StreamMessages.java
[`BoardReducer`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducer.java
[`BoardWriter`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`DeviceBreakers`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/DeviceBreakers.java
[`FlightDataRule`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/FlightDataRule.java
[`ModularityTest`]: ../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/ModularityTest.java
[`ObservationMapper`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/ObservationMapper.java
[`PollDispatcher`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollDispatcher.java
[`PollPlan`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/PollPlan.java
[`PollScheduler.onRepoll`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[`PollScheduler`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[`ServiceEvaluator`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluator.java
[`ServiceRule`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceRule.java
[`SseBroadcaster`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseBroadcaster.java
[`SseClient`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseClient.java
[`StatusDeriver`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StatusDeriver.java
[`StoredEvent`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StoredEvent.java
[`SurveillanceRule`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/SurveillanceRule.java
