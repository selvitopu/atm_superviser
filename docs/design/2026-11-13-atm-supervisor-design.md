# ATM Supervisor: Design

*SNMP-based technical supervision for a simulated air traffic control centre.*

Status: approved 2026-11-13 · Date: 2026-11-13

> **Amended by** [`docs/refactor/ATM-Supervisor-Architecture-Review-Decisions.md`](../refactor/ATM-Supervisor-Architecture-Review-Decisions.md) (2026-11-13). It adds a per-device circuit breaker with independent probes, single-flight polling with generation checks, crash-safe idempotent commands, SSE backpressure, and 30-second telemetry sampling. **Where this document and the amendment conflict, the amendment wins.**

---

## 1. Intent

ATM Supervisor is an open-source technical supervision tool for a simulated air traffic control centre. It watches the technical systems that air traffic control depends on, raises and manages their alarms, and shows what the equipment state means for the operational services. Its user is a System Controller at a technical supervision position in an ops room.

It sets out to do three things properly:

1. **SNMP plus REST system management**: polling, traps and informs, SET-based control, SNMPv3 security, and a custom MIB.
2. **A faithful model of the ATM/CNS domain**: which technical systems a control centre runs, how they are made redundant, what "degraded mode" means, and how a System Controller works with alarms.
3. **Safety-minded engineering**: never showing stale data as healthy, keeping replay clearly separate from live, interlocks on control commands, an audit trail, and a hazard log.

Around these it uses mainstream engineering: full-stack Java/Spring Boot plus Angular, reliable event handling (state change and event log in one transaction, catch-up after a lost connection), testing, Docker, and Prometheus/Grafana.

**Who it is for:** System Controllers, as the product's users, and engineers who want to study, run or extend an SNMP supervision design. The one-command demo (G7) runs the whole centre on a laptop.

**What success looks like:** a credible mini technical supervision tool that runs from one command, behaves the way the domain expects, and whose every component can be explained line by line.

## 2. Goals, non-goals, anti-goals

### Goals
- **G1 SNMP manager:** GET and GETBULK polling, SNMPv2c/v3 traps and informs, SET commands, SNMPv3 USM authPriv, a custom SMIv2 MIB that passes `smilint`, and the standard IF-MIB on the network switches.
- **G2 REST API** for system management, documented with OpenAPI.
- **G3 Angular operator UI:**
  - a service view, an equipment board, an alarm list with acknowledge, and an equipment detail panel
  - control commands with confirmation
  - replay
- **G4 Domain realism:**
  - CNS/ATM subsystems with A/B redundancy and automatic switchover
  - service impact derived from equipment state
  - lost-contact handling
  - alarm resynchronisation after traps are lost
- **G5 Replay and data retrieval:** rebuild the board as it was at any past moment, play history back at speed, and export an incident report.
- **G6 Engineering hygiene:**
  - unit tests, plus integration tests over real UDP SNMP and Testcontainers MySQL
  - CI and Docker Compose
  - Prometheus (including `snmp_exporter`) and Grafana
  - English docs: ADRs, a domain primer, a sample change request with a safety assessment, and a hazard log
- **G7 One-command demo:** `docker compose up` gives a working demo in under 2 minutes on a laptop, driven by scripted fault scenarios.

### Non-goals
- **Not real systems:**
  - It is not a certified or safety-qualified system.
  - It does not process real surveillance data such as ASTERIX. Radar and tracker data appear only as counters and rates.
  - It has no real hardware, no multi-site support, and no high availability for the supervisor itself (an ADR lists this as future work).
- **Not a product:**
  - It has no user management UI. The two demo users come from the environment.
  - It has no mobile layout. Ops consoles are large screens, so the target is 1920×1080 with a minimum of 1280×720.
  - It is English only, with no i18n.
- **Out of scope for v1:** Kubernetes manifests.

### Anti-goals (failure even if it "works")
- **No real names or branding:** using the name, logo or branding of any real ANSP or organisation, or implying the project mirrors anyone's internal system. The centre is fictional: **"Demo ACC"**.
- **Never green when unknown:** the UI must never show green or "OK" for something whose state is unknown or stale.
- **Must read as ATM:** a generic "network monitoring dashboard" look is a failure.
- **No unexplainable code:** every component must be something a maintainer can explain and defend in a code review. No framework magic for its own sake.
- **Self-contained demo:** once the images are built, the demo must not need internet access or manual setup steps.

## 3. Constraints

| Area | Decision |
|---|---|
| Java | **Java 25** (Eclipse Temurin 25) |
| Backend | Spring Boot **4.1.x** (4.1.2 is the latest GA at time of writing). Spring Modulith 2.1.x provides module structure, verification and docs only, not its event registry. Verify compatibility when planning. |
| SNMP | SNMP4J **3.13.x** (Apache-2.0). The simulator's agents are hand-written on SNMP4J core (§7). SNMP4J-Agent is **not** used. |
| Frontend | Angular **22**, TypeScript, standalone components, signals, zoneless change detection, and Angular CDK. No Angular Material: the look is custom. |
| Node | **Node 26 LTS** (Angular 22 needs `^22.22.3 \|\| ^24.15.0 \|\| >=26`). Development uses Node 26.13.1 and Angular CLI 22.2.7. |
| Database | MySQL **8.4 LTS**, Spring Data JPA/Hibernate, Flyway |
| Build | Maven multi-module for Java, npm for `ui/` |
| Images | `eclipse-temurin:25-jre` for the Java apps, `nginx` (stable alpine) for the UI |
| Runtime | One laptop (macOS arm64 and Linux amd64), whole stack under about 3 GB RAM, everything offline once the images are built |
| Licence | Apache-2.0 |
| Language | All code, comments, docs and UI text in English. All times are shown in UTC with a `Z` suffix (aviation convention), and containers run with `TZ=UTC`. |
| Releases | Work is phased (§13) so every phase ends with a usable tagged release. |

## 4. Approaches considered

1. **Modular monolith supervisor plus a separate simulator (chosen).** There are two Spring Boot apps:
   - The **simulator** hosts 15 fake devices as SNMP agents.
   - The **supervisor** polls them, receives traps, manages alarms, sends SET commands, stores an event log in MySQL, and serves REST and SSE to Angular.

   Prometheus and Grafana run alongside. Spring Modulith enforces the module boundaries inside the supervisor. *Why:* it has few moving parts, it touches every requirement, it fits the time available, and it is easy to explain. The boundaries are clean enough that a module could later move behind a broker.
2. **Broker-centred microservices** (collector → Kafka/RabbitMQ → state, history/replay and control services). This would show middleware, and replay falls out of the Kafka log. It was rejected because four services for 15 devices is over-engineering that is hard to justify, and it roughly doubles the build time.
3. **Assembling existing tools** (snmpsim, snmp_exporter, Grafana, Alertmanager, plus a thin trap service). It would be fast, but it was rejected because the hard parts (service derivation, alarm reconciliation, safe `UNKNOWN` handling) would end up as glue between tools that do not model them.

Approach 1 was chosen on 2026-11-13.

## 5. The simulated centre: Demo ACC

Demo ACC is a fictional upper-area control centre with 15 simulated devices. Each device is a separate SNMP agent (its own UDP port and engine ID) inside the simulator process.

| ID | Type | What it is | Redundancy | Key values |
|---|---|---|---|---|
| `RAD-N`, `RAD-E`, `RAD-S` | radar | MSSR/Mode S radar stations feeding the tracker | Each station has transmitter channels A and B. Stations overlap in coverage. | status, active channel, channel A/B status, rotation period (ms, nominal 4000–4800), plots per scan (nominal ~300) |
| `ADSB-GW` | adsb | Gateway of the ADS-B ground-station network | single | targets per second, stations up / total (e.g. 11/12) |
| `SDPS-A`, `SDPS-B` | sdps | Surveillance data processing (multi-sensor tracker) | Active/standby pair. Automatic switchover 3 s after the active unit fails. | role, track count, latency (ms), sensor input table (one row per radar plus ADS-B: receiving / lost / excluded) |
| `FDPS-A`, `FDPS-B` | fdps | Flight data processing | Active/standby pair with automatic switchover | role, active flight plans, inbound message queue depth (AFTN/OLDI), latency (ms) |
| `VCS-A`, `VCS-B` | vcs | Voice communication system | Hot/hot: both active, each can carry all positions | positions active / total, radio link status |
| `RADIO-1`, `RADIO-2` | radio | Remote radio sites | Main and standby transmitter per frequency, switched inside the device | frequency table: frequency (e.g. `132.855`), sector, main TX status, standby TX status, active TX, RX status |
| `REC` | recorder | Legal voice and data recorder | single | channels recording / total, disk usage %, last archive time |
| `LAN-A`, `LAN-B` | switch | Ops LAN core switches | Dual LAN | **standard IF-MIB** (`ifTable`, `ifOperStatus`) plus standard `linkDown`/`linkUp` traps. **SNMPv2c only**, deliberately, to show handling of legacy equipment. |

All devices also implement the SNMPv2-MIB `system` group (`sysDescr`, `sysUpTime`, `sysName`, …).

### 5.1 Equipment status model

Each piece of equipment has four independent attributes:

- **Health**, one of `OPERATIONAL`, `DEGRADED`, `FAILED` or `UNKNOWN`.
  - Custom-MIB devices report the first three themselves in `atmTsEqStatus`. For the switches, health is derived from IF-MIB (§5.3).
  - **The supervisor adds `UNKNOWN`.** `UNKNOWN` always overrides the last known value, and it always carries an `unknownReason`:
    - `NOT_YET_POLLED`: before the first successful poll.
    - `LOST_CONTACT`: after 3 consecutive failed polls, about 15 s.
    - `RESTARTING`: during a commanded restart (§8.3).
    - `SUPERVISOR_RESTARTED`: after the supervisor itself restarts, until the first poll.
- **Maintenance**, a boolean flag that is *not* a health value. It comes from `atmTsEqMaintenanceMode`. Because the two are separate, a device in maintenance still shows its real health. In the `fdps-failover` demo the operator sees FDPS-A come back `OPERATIONAL` after the restart, and only then switches maintenance off.
- **Role**, one of `standalone`, `active` or `standby`.
- **Contact**, made of `reachable`, `lastPollAt` and the count of consecutive poll failures.

**Freshness:**
- At more than 2× the poll interval since `lastPollAt`, the UI shows the age in amber. The UI computes the age itself (§9).
- At 3 failed polls, health becomes `UNKNOWN` and alarm 901 `LOST_CONTACT` is raised.

**Availability**, as used by the service rules: a device is *available* when its health is `OPERATIONAL` or `DEGRADED` and it is **not** in maintenance. Maintenance counts as a planned outage.

**Open alarms while a device is UNKNOWN** stay open and unchanged, because the supervisor cannot confirm that they cleared.
- **Display:** they get an "unconfirmed since 14:10:00Z" marker.
- **Not suppressed:** they are not suppressed or cleared.
- **Lost contact:** alarm 901 *Lost contact* is raised in addition.
- **Recovery:** when contact is restored, the resync (§8.2) settles them.

### 5.2 Operational services (the service view)

The supervisor derives five services from equipment state. Each rule is a small Java class implementing `ServiceRule`. The rules are unit-tested with table-driven tests and documented in `docs/domain.md`.

Each rule only defines FAILED and DEGRADED in terms of which equipment is **available** (§5.1). Every other case is `OK`.

| Service | FAILED when | DEGRADED when |
|---|---|---|
| `SURV` Surveillance | No SDPS unit is available, or no sensor input (radar or ADS-B) is available | Only one SDPS unit is available (redundancy lost), or fewer than 2 radars are available, or ADS-B is unavailable |
| `FD` Flight data | No FDPS unit is available | Only one FDPS unit is available |
| `VOICE` Voice | Both VCS units are unavailable, or any frequency has no working TX | One VCS unit is unavailable, or any frequency is running on its standby TX |
| `REC` Recording | The recorder is unavailable, or not all channels are recording | Disk usage is above 85% |
| `NET` Network | Both LANs are unavailable | One LAN is unavailable, or any critical switch interface is down (§5.3) |

**UNKNOWN is handled the same way for every rule.** A service is `UNKNOWN` only if the unknown equipment could change the answer.

1. **Optimistic pass:** evaluate the rule with every `UNKNOWN` device treated as available and in its nominal values.
2. **Pessimistic pass:** evaluate it again with every `UNKNOWN` device treated as unavailable.
3. **Equal results:** if both passes give the same result, that is the service state. A known failure therefore is never hidden behind "unknown". For example, RADIO-2 has no TX on `132.855` while RADIO-1 is UNKNOWN: both passes give FAILED, so VOICE shows FAILED.
4. **Different results:** the state is `UNKNOWN`, and it carries the optimistic result as `bestCase`. The UI shows, for example, "UNKNOWN · at best DEGRADED".

A service that is UNKNOWN is never shown as OK. The table-driven tests cover both passes and every row.

### 5.3 Network switches (IF-MIB, v2c)

The LAN switches do not implement the custom MIB. Their type `switch` comes from the inventory only.

- **Interfaces:** each switch has 8 interfaces. The inventory lists its `criticalInterfaces` (ifIndex 1–4 by default, the uplinks to server and position racks).
- **Polled columns:** a GETBULK on `ifTable` reads `ifDescr`, `ifAdminStatus`, `ifOperStatus`, `ifLastChange` and the octet counters, and `ifXTable` adds `ifAlias`.
- **Detail panel:** for each interface it shows `ifDescr` plus `ifAlias` (e.g. "Gi1/0/2 · uplink SDPS rack"), admin and oper status, and "down since". "Down since" is computed from `ifLastChange` against `sysUpTime`.
- **Interface state:** an interface is *down* when `ifOperStatus` is not `up(1)`.
  - If `ifAdminStatus` is `down(2)`, it is *disabled*: switched off on purpose, but its capacity is still lost.
- **Health:**
  - all critical interfaces up → `OPERATIONAL`
  - some critical interfaces down or disabled → `DEGRADED`
  - all critical interfaces down or disabled → `FAILED`
  - lost contact → `UNKNOWN`
- **Alarms** are raised by the supervisor itself (§8.2). The instance is the `ifIndex`.
  - **1001 *Interface down*:** the interface is admin up but oper down. Major for a critical interface, warning for any other.
  - **1002 *Interface disabled*:** the interface is admin down. Warning, because it is planned.
- **Traps:** the standard `linkDown` and `linkUp` traps carry `ifIndex`, `ifAdminStatus` and `ifOperStatus` (RFC 2863), so the interface is identified from the trap itself. As with every trap, the supervisor **always re-polls `ifTable` straight away** and decides from the poll, not from the trap alone.
- **Resync** re-reads `ifTable` and applies the reconciliation rules of §8.2 to the 1001 and 1002 alarms, because a v2c device has no alarm table to walk.
- **Scenario effect:** in `lost-contact`, `LINK_DOWN` on LAN-B ifIndex 2 makes LAN-B `DEGRADED`, raises 1001 as major, and makes `NET` `DEGRADED`.

## 6. The MIB: `DEMO-ATM-TECHSUP-MIB`

- **File:** `mib/DEMO-ATM-TECHSUP-MIB.txt`, in SMIv2. CI lints it with `smilint -l 3`.
- **Root OID:** `1.3.6.1.4.1.32473.1`. Enterprise number 32473 is the IANA number reserved for documentation and examples (RFC 5612), so the project uses no real organisation's number.
- **Textual conventions:**
  - `AtmTsEquipmentStatus ::= INTEGER { operational(1), degraded(2), failed(3) }`. This is health only. Maintenance is the separate `atmTsEqMaintenanceMode` flag, and `UNKNOWN` exists only in the supervisor.
  - `AtmTsSeverity` follows ITU X.733 / RFC 3877 ordering: `cleared(1), indeterminate(2), critical(3), major(4), minor(5), warning(6)`.

```
atmTechSupMIB (32473.1)
├── atmTsObjects (1)
│   ├── atmTsEquipment (1)   – on every custom-MIB device (all except the LAN switches)
│   │     atmTsEqId                DisplayString              RO
│   │     atmTsEqType              INTEGER {radar(1) adsb(2) sdps(3) fdps(4) vcs(5) radio(6) recorder(7)}  RO
│   │     atmTsEqStatus            AtmTsEquipmentStatus       RO
│   │     atmTsEqRole              INTEGER {standalone(1) active(2) standby(3)}  RO
│   │     atmTsEqStatusChangedAt   DateAndTime                RO
│   │     atmTsEqSwVersion         DisplayString              RO
│   │     atmTsEqMaintenanceMode   TruthValue                 RW
│   │     atmTsEqCommand           INTEGER {none(1) becomeActive(2) restart(3) resetCounters(4)}  RW  (reads back none(1))
│   │                              becomeActive is idempotent: on the standby unit it swaps the pair's roles;
│   │                              on the already-active unit it succeeds without doing anything
│   │     atmTsEqLastCommandResult INTEGER {none(1) inProgress(2) success(3) rejected(4)}       RO
│   ├── atmTsAlarms (2)      – active alarm table on every custom-MIB device (used for resync)
│   │     atmTsAlarmTable / atmTsAlarmEntry INDEX { atmTsAlarmCode, atmTsAlarmInstance }
│   │       atmTsAlarmCode          Unsigned32      not-accessible (index)
│   │       atmTsAlarmInstance      Unsigned32      not-accessible (index) – 0 = whole equipment,
│   │                                               else sensor index / frequency index / channel number
│   │       atmTsAlarmInstanceName  DisplayString   RO  e.g. "RAD-N", "132.855", "" for 0
│   │       atmTsAlarmSeverity      AtmTsSeverity   RO
│   │       atmTsAlarmText          DisplayString   RO
│   │       atmTsAlarmRaisedAt      DateAndTime     RO
│   ├── atmTsRadar (3)       rotationPeriodMs, plotsPerScan, activeChannel, channelAStatus, channelBStatus
│   ├── atmTsAdsb (4)        targetsPerSecond, stationsUp, stationsTotal
│   ├── atmTsSdps (5)        trackCount, latencyMs, sensorTable{index, name, inputStatus{receiving(1) lost(2) excluded(3)}}
│   ├── atmTsFdps (6)        flightPlanCount, queueDepth, latencyMs
│   ├── atmTsVcs (7)         positionsActive, positionsTotal, radioLinkTable{index, siteName, linkStatus}
│   ├── atmTsRadio (8)       freqTable{index, freqMHz, sector, mainTxStatus, standbyTxStatus, activeTx{main(1) standby(2)}, rxStatus}
│   └── atmTsRecorder (9)    channelsRecording, channelsTotal, diskUsagePercent, lastArchiveAt
├── atmTsNotifications (2) → (2.0)
│     atmTsEqStatusChange   OBJECTS { atmTsEqId, atmTsEqStatus, atmTsEqRole }
│     atmTsAlarmRaised      OBJECTS { atmTsEqId, atmTsNotifAlarmCode, atmTsNotifAlarmInstance,
│                                     atmTsAlarmInstanceName, atmTsAlarmSeverity, atmTsAlarmText }
│     atmTsAlarmCleared     OBJECTS { atmTsEqId, atmTsNotifAlarmCode, atmTsNotifAlarmInstance }
│     atmTsSwitchover       OBJECTS { atmTsEqId, atmTsEqRole }
└── atmTsConformance (3)     compliance statement, object groups, notification groups
```

`atmTsNotifAlarmCode` and `atmTsNotifAlarmInstance` are `accessible-for-notify` scalars. They carry the alarm identity in notifications, because the table index columns themselves are not accessible.

**An alarm is identified by `(equipment, code, instance)`.** This lets SDPS hold *Sensor input lost* for RAD-N and for RAD-E as two separate alarms at the same time, and lets a radio site hold one TX alarm per frequency.

A shared Maven module `mib` holds the MIB file and the Java constants (`AtmTsOids` plus enums). The simulator and the supervisor both use it, so OIDs cannot drift apart. How to keep the constants in sync with the MIB file is left to the builder; a simple test that parses the OID assignments is enough.

### 6.1 SNMP protocol choices
- **Security:** the custom-MIB devices use SNMPv3 USM **authPriv** with SHA-256 and AES-128. There are two users:
  - `monitor`: read only.
  - `operator`: read, plus SET on `atmTsEqMaintenanceMode` and `atmTsEqCommand`. The agent's SET handler enforces this by checking the request's security name (§7). Full VACM would be overkill for two writable objects.

  The LAN switches use v2c with a read-only community.
- **Credentials** come from environment variables. `.env.example` holds demo values, and real `.env` files are git-ignored.
- **Fixed engine IDs:** each device has a fixed engine ID in the inventory. This makes v3 trap and inform reception deterministic, because the receiver can localise USM keys for each known engine.
- **`snmpEngineBoots` must be persisted.** RFC 3414 requires it to grow on every restart.
  - **How:** the simulator keeps each device's boots counter in a JSON file on a Docker volume (`/data/engine-boots.json`). It increments the counter on every simulator start and on every simulated device restart, and resets `snmpEngineTime` and `sysUpTime` at the same time.
  - **Why it matters:** localised keys depend only on the engine ID, so they stay valid across restarts. What breaks is the **time window**. If the boots counter went back down after a simulator restart, the supervisor's USM would treat that device's v3 traps as replays and silently drop them. Requests are not affected, because SNMP4J resynchronises automatically on the `notInTimeWindow` report.
  - **Test:** restart a device and confirm its next v3 trap is still accepted.
- **Traps and informs:** critical alarms are sent as **INFORMs** (acknowledged and retried). Everything else is sent as TRAPs.
- **Polling is the source of truth.** Traps are low-latency hints: a trap triggers an immediate re-poll of that device. After contact is restored, or after the supervisor starts, it walks each device's `atmTsAlarmTable` and reconciles its own alarm state with it. This covers lost traps.
- **Ports:**
  - Each agent listens on UDP `16101 + n` (n = 0…14, so 16101–16115) inside the simulator container, so the apps run as non-root. This range was chosen so that no agent collides with the trap port 10162 when everything runs on one host.
  - The supervisor's trap receiver listens on UDP `10162`.
  - Agent ports are published to the host so the author can run `snmpwalk` live in the demo.
- **Poll timing:** default interval 5 s with ±10% jitter, timeout 1500 ms, 1 retry. Tables are read with GETBULK.
- **SETs are sent with 0 retries** and a 3 s timeout, so a lost response can never cause a second execution. A timeout means the outcome is *uncertain*, and polling settles it (§8.3).
- **One varbind per SET.** The agent still validates every varbind before applying any of them, so a multi-varbind SET is all-or-nothing as the protocol requires.

## 7. Simulator

`simulator/` is a Spring Boot app. Its agents are **hand-written on SNMP4J core**, not built on SNMP4J-Agent.

**Why:** what SNMP4J-Agent provides is not needed here.
- The devices expose **read-only** tables that the simulator's own models fill. Rows appear and disappear when the model changes, never through SNMP, so there is no `RowStatus` row creation.
- Only two objects are writable, so VACM configuration and persistent agent config files are unnecessary.

Skipping SNMP4J-Agent avoids its sparsely documented API and keeps every line of the agent explainable.

**Per device:**
- **SNMP stack:** one SNMP4J `Snmp` instance with its own UDP transport (port `16101 + n`). It has its own `MPv3` with the device's fixed local engine ID and persisted boots counter (§6.1), and its own `USM` holding the users `monitor` and `operator`. The switches use a v2c community instead.
- **`MibView`:** an immutable `NavigableMap<OID, Variable>` snapshot.
  - **Rebuilt on change:** the device model builds a new snapshot on every change and swaps it in atomically (copy-on-write). A device has about 50–150 entries, so rebuilding is cheap.
  - **Tables are plain map entries** of the form `column.index`. Adding or removing an alarm row just means the next snapshot has or lacks those entries.
  - **Shared groups:** the SNMPv2-MIB `system` group and, for the switches, the IF-MIB tables live in the same map.
- **`DeviceResponder implements CommandResponder`:**
  - **GET:** exact lookup. A missing value returns `noSuchObject` or `noSuchInstance`.
  - **GETNEXT:** `higherEntry`. If there is none, it returns `endOfMibView`.
  - **GETBULK:** handles non-repeaters and max-repetitions over `higherEntry`, and stops adding varbinds before the response would exceed the transport's maximum message size.
  - **SET:** only the two writable OIDs. The checks run in this order:
    1. The security name must be `operator`, else `noAccess`.
    2. The OID must be writable, else `notWritable`.
    3. The value must have the right type, else `wrongType`.
    4. The value must be in range, else `wrongValue`.
    5. The model must accept it, else `inconsistentValue`, for example `restart` while not in maintenance.

    All varbinds are validated first and applied only after that.
  - **Restart:** while the device is "restarting", the responder drops every request.
  - **v3 security:** unknown users and wrong passwords are handled by SNMP4J's USM, which answers with report PDUs.
- **Notifications:** TRAP and INFORM PDUs are built from the current snapshot plus the `accessible-for-notify` varbinds. Those varbinds never appear in the map; they exist only inside notifications. The notification target is `supervisor:10162`.

**Size:** the responder plus PDU handling is about 400 lines with tests, roughly one to two evenings. The day-one spike (§14) builds exactly this for one device.

- **Agents:** the simulator reads the shared `config/inventory.yml` and starts one device stack, as above, per device.
- **Device models:** each device type has a model class holding its state and simple dynamics. Values drift with bounded noise; for example, track count follows a slow sine plus noise. Every status change updates the active alarm table and sends the right notification.
- **Redundancy:** pair coordination lives in the simulator. An SDPS or FDPS pair object performs automatic switchover 3 s after the active unit fails and sends `atmTsSwitchover`. A SET of `becomeActive` on the standby unit swaps the roles, but is rejected with `inconsistentValue` if that unit is not operational. On the unit that is already active, the same SET succeeds without doing anything. A radio site switches to its standby TX on its own.
- **Command effects:**
  - **Maintenance:** setting `atmTsEqMaintenanceMode` changes only the flag, never health.
  - **Restart:** a `restart` SET, which the device accepts only in maintenance, is answered `noError` first. The agent then stops answering for 8 s. It comes back with `snmpEngineBoots` incremented, `sysUpTime` reset, `OPERATIONAL` health and its injected faults cleared. If it belongs to a pair, it comes back in the `standby` role.
  - **Reset counters:** `resetCounters` zeroes the device's counters.
- **Fault injection** is available through the REST API and through scenarios:
  - `FAIL`, `DEGRADE`, `RECOVER`
  - `CHANNEL_A_FAIL`, `CHANNEL_B_FAIL` (radar)
  - `TX_MAIN_FAIL`, `TX_STANDBY_FAIL` (radio, per frequency)
  - `SENSOR_LOST` (SDPS input)
  - `DISK_FILL` (recorder)
  - `LINK_DOWN` (switch interface)
  - `UNREACHABLE`: the agent stops answering
  - `DROP_TRAPS`: notifications are silently dropped, to show resync
- **REST API** on internal port 8090, proxied by nginx under `/sim/api`. It is a training tool and is deliberately unauthenticated; the docs say so.
  - `GET /devices`, `POST /devices/{id}/faults {fault, target?}`, `POST /reset`
  - `GET /scenarios`, `POST /scenarios/{name}/run`, `POST /scenarios/stop`
- **Scenarios** live in `simulator/src/main/resources/scenarios/*.yaml` as timed steps, e.g. `{ at: 5s, device: RAD-N, fault: FAIL }`:
  1. `radar-outage`
     - RAD-N fails, which raises SENSOR_LOST on SDPS. Two radars are left, so the service stays OK with a minor alarm.
     - At 20 s RAD-E degrades and then fails, so `SURV` becomes DEGRADED.
     - Both recover at 90 s.
  2. `fdps-failover`
     - FDPS-A fails, the pair switches over automatically to B, and `FD` becomes DEGRADED (redundancy lost).
     - The scenario then waits for the operator to act. The demo shows maintenance, restart and recovery of A as standby.
  3. `radio-tx-failure`
     - RADIO-2 `132.855` main TX fails, standby takes over, and VOICE becomes DEGRADED.
     - At 40 s the standby TX fails as well, so the frequency has no TX: a CRITICAL alarm sent as an INFORM, and `VOICE` becomes FAILED.
  4. `lost-contact`
     - REC goes `UNREACHABLE` with `DROP_TRAPS`, and LAN-B gets `LINK_DOWN`.
     - REC shows UNKNOWN, never green.
     - At 45 s REC comes back with a new disk alarm that was never trapped, and the supervisor's resync finds it.
  5. `nominal`: reset everything to all green.

## 8. Supervisor

`supervisor/` is a Spring Boot app organised as Spring Modulith modules. `ApplicationModules.verify()` runs as a test, and the module documentation is generated into `docs/`.

| Module | Responsibility |
|---|---|
| `inventory` | Loads and validates `config/inventory.yml` (id, type, name, group, host, port, SNMP version, user or community, engine ID, poll interval, pair partner). Fails fast at startup if it is invalid. |
| `snmp` | One SNMP4J `Snmp` session (UDP transport, MPv2c plus MPv3, USM). `SnmpClient` covers get, getBulk and set with timeouts and retries. `TrapReceiver` accepts traps and informs and answers informs. Maps varbinds to typed `Observation` and `Notification` records. |
| `polling` | One scheduled task per device, run on virtual threads with bounded concurrency. Counts consecutive failures, triggers an immediate re-poll when a trap arrives, and runs the alarm-table resync. |
| `board` | Owns the live state (equipment, services, open alarms) and the **board writer** thread (§8.1). Also holds:<br>- the `StatusDeriver`<br>- the **`ServiceEvaluator`**: the single function `Board → service states` that runs every `ServiceRule` with both UNKNOWN passes. The board writer and the command preview both use it (§8.3).<br>- the **pure `BoardReducer`** (`Board × Event → Board`) |
| `alarms` | Alarm lifecycle and catalogue (§8.2), deduplication, maintenance suppression, and reconciliation on resync. |
| `control` | Commands, interlocks, SET execution, and verifying the result (§8.3). |
| `history` | Append-only event log, snapshots, replay queries, export, and retention purge. |
| `api` | REST controllers, the SSE endpoint, DTOs, OpenAPI (springdoc), and RFC 9457 `ProblemDetail` errors. |
| `security` | Spring Security with session login, CSRF cookie, and roles. |

### 8.1 Event flow, threading and transactions

**All inputs go through one board writer thread, in order.** These inputs are:
- poll results and poll failures
- traps and informs
- acknowledgements from the API
- command requests and command results
- timer ticks (heartbeat, snapshot)

For 15 devices this comes to a few inputs per second, which one thread handles easily. Ordering is then deterministic and there are no races. For each input, the board writer:

```
input ─▶ 1. derive   StatusDeriver compares with the current in-memory Board,
                     ServiceRules recompute affected services,
                     alarm logic decides raise / clear / dedupe (needs current open alarms),
                     command logic checks interlocks / correlates results
       ─▶ 2. commit  ONE transaction: event_log rows + alarm rows + command rows
       ─▶ 3. apply   BoardReducer folds the committed events into the in-memory Board
       ─▶ 4. publish after commit: SSE broadcast (api), metrics, follow-up actions
                     (e.g. "trap received → re-poll now" is enqueued back to polling)
```

**Consequences:**
- A derived event, such as `SERVICE_STATE_CHANGED` or alarms 903 and 904, always commits in the same transaction as the change that caused it.
- **The live board is exactly the event log folded in `id` order.** Replay relies on this.
- **No outbox is needed.** `event_log` is the system of record, written in the same transaction as the state change. SSE is derived from committed events, and a client that misses some catches up with `Last-Event-ID` or a refetch. If a broker is added later (ADR 0001), `event_log` becomes the outbox through a relay.
- Spring Modulith is used only for module structure, the verification test and the generated docs. Its event publication registry is not used.

**Events are facts, not inputs to recompute.** Each stored event's `payload` carries the **full new state of the entity it changed**:
- the whole equipment state: health, maintenance, role, contact, key values
- the whole service state: state plus `bestCase`
- the whole alarm record

The `BoardReducer` is therefore a plain upsert by entity id. It never runs service rules or alarm logic, which run only in step 1 and only live.

This has three consequences:
- **Replay shows what operators saw at the time**, not what today's rules would compute.
- **Alarms are part of replay.**
- **The browser's delta handling is the same upsert**, so the Java fold and the browser cannot disagree. Neither contains rules.

**Event types:**
- **Equipment, service and values:**
  - `EQUIPMENT_STATE_CHANGED`
  - `SERVICE_STATE_CHANGED`
  - `TELEMETRY_SAMPLED`: a timed sample, stored only (amendment §5). See the telemetry channel below.
- **Contact:** `CONTACT_LOST`, `CONTACT_RESTORED`
- **Alarms:** `ALARM_RAISED`, `ALARM_UPDATED` (severity or text changed), `ALARM_ACKNOWLEDGED`, `ALARM_CLEARED`, `ALARM_CLOSED`
- **Commands:** `COMMAND_ISSUED`, `COMMAND_COMPLETED`
- **Supervisor lifecycle:** `SUPERVISOR_STARTED`, `SUPERVISOR_HEARTBEAT` (every 60 s), `SUPERVISOR_STOPPING` (on graceful shutdown)

**Lost contact in replay.** The consecutive-failure count is **live only**. It changes on every failed poll and is not stored. What is stored is the fact:
- `CONTACT_LOST` when the third poll fails. Its payload is the full equipment state: health `UNKNOWN`, `unknownReason: LOST_CONTACT`, `lostAt`, and `lastContactAt` (the last successful poll).
- `CONTACT_RESTORED`, carrying the fresh state, on the next successful poll.

*Worked example:* REC is polled successfully at 14:09:45Z, fails at 14:09:50, 14:09:55 and 14:10:00Z, and answers again at 14:10:40Z.

| Replay time T | REC shows | Its open alarms |
|---|---|---|
| 14:09:45 ≤ T < 14:10:00 | Last stored state. Replay shows no freshness ages, and the replay band says so. | as they were |
| 14:10:00 ≤ T < 14:10:40 | `? UNKNOWN since 14:10:00Z · last contact 14:09:45Z`, with last known values greyed and labelled "last known". This is identical to what live showed. | still listed, with the "unconfirmed" marker, plus 901 *Lost contact* |
| T ≥ 14:10:40 | Fresh state, then any changes from the resync | settled by the resync |

The replay fidelity test (§11) uses exactly this sequence.

**Supervisor downtime in replay.** When the reducer applies `SUPERVISOR_STARTED`, it sets every device to `UNKNOWN`, which is also what live showed. When the board is rebuilt for time T and no event or heartbeat exists in the 90 s before T, the whole board is marked `NO_DATA`. The UI then shows "No recorded data: supervisor not running". Replay therefore never shows a stale green board for a period when nothing was being supervised.

**Live telemetry is not stored.** Every successful poll produces a `telemetry` SSE message: `{equipmentId, lastPollAt, values, serverTime}`. It has **no SSE id**, so it does not move `Last-Event-ID`, and it is not written to `event_log`.
- **Live board:** values and freshness come from telemetry at poll rate. The UI ticks freshness every second using `lastPollAt`, corrected by the client/server clock offset taken from `serverTime`.
- **Stored log:** gets one `TELEMETRY_SAMPLED` event per device every 30 s while successful observations are available. There is **no deadband**, so stable values are sampled too (amendment §5). Missed polls produce no sample, and nothing is ever fabricated.
- **What replay shows:** statuses, alarms and commands exactly. For values, it shows the most recent real sample at or before T, together with that sample's own timestamp and age, and never interpolates. The replay UI says so.
- **Not affected:** health, `UNKNOWN` and lost contact are never coalesced. They are stored events.

**On supervisor restart**, it writes `SUPERVISOR_STARTED`, which marks every device `UNKNOWN`. It then rebuilds open alarms from the `alarm` table and resyncs each device on its first successful poll: the alarm table walk for custom-MIB devices, the `ifTable` for the switches.

### 8.2 Alarms

**Lifecycle:** `ACTIVE_UNACK → ACTIVE_ACK → CLEARED` or `ACTIVE_UNACK → CLEARED_UNACK → CLEARED`. An alarm closes only when it is both cleared and acknowledged, which is standard alarm-handling practice.

**Rules:**
- **Identity and deduplication:** an alarm is identified by `(equipment, code, instance)`, with `instance` 0 for the whole equipment. There is at most one open alarm per identity.
- **Severity changes:** if a raise arrives for an open alarm with a different severity, the supervisor updates the alarm (`ALARM_UPDATED`).
  - **Escalation** (for example disk 801 going from minor to major): an acknowledged alarm goes back to **unacknowledged**, because operators must see it.
  - **De-escalation:** the acknowledgement is kept.
- **Who can acknowledge:** only `SUPERVISOR` can acknowledge, singly or in bulk, with an optional comment.
- **Maintenance suppression:** alarms from a device in maintenance are still recorded, but flagged `suppressed` and hidden by default. This avoids alarm floods during planned work.
- **Source of each alarm:** `DEVICE` (from a trap or the alarm table) or `SUPERVISOR` (derived).

**Resync (reconciliation).** For custom-MIB devices, resync runs:
- when contact is restored
- when the supervisor starts, on each device's first successful poll
- as a background check every 5 minutes

It walks the **whole** `atmTsAlarmTable`.
- **Partial walk:** if the walk does not complete (a timeout midway, or the device lost again), **nothing changes** and resync retries at the next poll. A partial walk must never clear alarms.
- **Comparison:** a complete walk gives the device's set **D**, keyed by `(code, instance)`. The supervisor's set **S** is its open alarms for that device with source `DEVICE`.
- **One transaction:** the whole comparison is a single board-writer input, so its events commit in one transaction.

| Case | Action | Event |
|---|---|---|
| In D, not in S | Raise, using the device's `raisedAt` | `ALARM_RAISED` with `via: RESYNC` |
| In S, not in D | Clear | `ALARM_CLEARED` with `via: RESYNC` |
| In both, D more severe | Update severity, back to unacknowledged | `ALARM_UPDATED` |
| In both, D less severe | Update severity, acknowledgement kept | `ALARM_UPDATED` |
| In both, same severity (text may differ) | Update the text only if it differs | `ALARM_UPDATED` or nothing |

**Out of scope for the walk:**
- **Supervisor-sourced alarms:** the device walk never touches them (9xx, 1001, 1002).
  - **1001 and 1002:** the switches apply the same table to their interface alarms, using the re-read `ifTable` as D.
  - **901:** `CONTACT_RESTORED` clears it.
- **Duplicates:** `open_key` uniqueness (§8.4) is the safety net, but the table above is what prevents them.

Every row of the table, plus the partial-walk case, has its own unit test.

**Starter catalogue** (the builder may extend it):

| Code | Source | Alarm | Severity |
|---|---|---|---|
| 101 | device | Equipment failed | critical |
| 102 | device | Equipment degraded | minor |
| 103 | device | Maintenance mode on | warning |
| 104 | device | Switchover occurred | warning |
| 201 / 202 | radar | Channel A / B failed | minor (major if the other channel is also failed) |
| 203 | radar | Rotation period out of tolerance | major |
| 204 | radar | Low plot rate | minor |
| 301 | adsb | Ground stations down | minor (major if fewer than half are up) |
| 401 | sdps | Sensor input lost | minor |
| 402 | sdps | Processing latency high (> 500 ms) | major |
| 501 | fdps | Message queue backlog (> 200) | major |
| 502 | fdps | Processing latency high (> 1000 ms) | major |
| 601 | vcs | Positions unavailable | major |
| 701 | radio | Main TX failed | major |
| 702 | radio | Standby TX failed | minor |
| 703 | radio | No TX available on frequency | critical (sent as INFORM) |
| 704 | radio | RX failed | major |
| 801 | recorder | Disk usage high | minor at > 85%, major at > 95% |
| 802 | recorder | Channels not recording | critical (sent as INFORM) |
| 901 | supervisor | Lost contact | major |
| 902 | supervisor | SNMP authentication failure (USM report, e.g. wrong digest) | major |
| 903 | supervisor | Service redundancy lost | major |
| 904 | supervisor | Service failed | critical |
| 1001 | supervisor (switch) | Interface down (admin up, oper down), instance = ifIndex | major for a critical interface, warning otherwise |
| 1002 | supervisor (switch) | Interface disabled (admin down), instance = ifIndex | warning |

**Instances:**
- 401: sensor index
- 701–704: frequency index
- 802: channel number
- 1001, 1002: ifIndex
- everything else: 0

### 8.3 Control commands

The commands are `SWITCHOVER`, `MAINTENANCE_ON`, `MAINTENANCE_OFF`, `RESTART` and `RESET_COUNTERS`.

- **Who can send them:** only users with the `SUPERVISOR` role. A free-text **reason** of at least 5 characters is mandatory.
- **Supervisor-side interlocks**, checked before any SET is sent (rejected with `409` and a clear message):
  - `SWITCHOVER` needs the partner's health to be `OPERATIONAL` and the partner not to be in maintenance.
  - `MAINTENANCE_ON` is refused on the **active** unit of a pair: switch over first.
  - `RESTART` is only allowed while the device is in maintenance.
  - Every command is refused while the device is `UNKNOWN`.
- **Confirmation:** the UI shows a confirmation dialog with the equipment, the command and the **predicted service impact**.
- **How the preview is computed:** `POST …/commands/preview` copies the live board and applies the command's expected effect as equipment-state upserts. It then runs the **same `ServiceEvaluator`** that the board writer uses in step 1 (§8.1); there is no second implementation of the rules. The expected effects are:
  - `MAINTENANCE_ON`: maintenance becomes true.
  - `MAINTENANCE_OFF`: maintenance becomes false.
  - `SWITCHOVER`: the pair's roles swap.
  - `RESTART`: health becomes `UNKNOWN` with reason `RESTARTING`, which shows the impact *during* the restart.
  - `RESET_COUNTERS`: no effect.

  An integration test runs each command from the scenarios and checks that the preview's service states equal the service states actually observed once the command completes.
- **Execution:** the request returns `202` with a command id. `SWITCHOVER` is sent as `becomeActive` to the current **standby** unit, which makes it idempotent. Every step goes to the audit log, including the actor.

**SET outcome** (§6.1 explains why SETs use 0 retries):

| SNMP result of the SET | Command status | Shown to the operator as |
|---|---|---|
| `noError` | `IN_PROGRESS`, then verification (below) | "Sent, verifying…" |
| `wrongType`, `wrongValue`, `badValue` or `inconsistentValue` | `REJECTED` | "Refused by device: not in maintenance" (the device's reason, from `atmTsEqLastCommandResult` and the error status) |
| `noAccess`, `notWritable`, `authorizationError`, or a USM report (unknown user, wrong digest) | `FAILED` | "Not permitted: configuration error". A USM failure also raises alarm 902. |
| `genErr`, `commitFailed`, `undoFailed` or `resourceUnavailable` | `FAILED` | "Device error: genErr" |
| Timeout (no response) | `UNCERTAIN`, then verification decides | "No response, checking whether it was applied…" |

**Verification**, by polling:

| Command | Verified when | Window |
|---|---|---|
| `MAINTENANCE_ON` / `MAINTENANCE_OFF` | A poll shows `atmTsEqMaintenanceMode` at the new value | 10 s |
| `SWITCHOVER` | Both units of the pair are polled with their roles swapped | 10 s |
| `RESTART` | The device answers again with a `sysUpTime` **lower** than before the command, which proves the restart, and `atmTsEqLastCommandResult` = `success` | 30 s |
| `RESET_COUNTERS` | `atmTsEqLastCommandResult` = `success` | 10 s |

Results:
- **Inside the window:** a match gives `COMPLETED`.
- **Window expires:** after `noError` the status becomes `TIMED_OUT`. After `UNCERTAIN` it **stays `UNCERTAIN` (unknown outcome)**: the device's command exclusion is kept until an audited manual resolution releases it (amendment §3).
- **Restart:** when `RESTART` is accepted, the supervisor immediately sets the device's health to `UNKNOWN` with reason `RESTARTING`. Failed polls inside the 30 s window are expected and do not count toward lost contact. The first successful poll then sets real health. A restarting device is therefore never shown with its old, pre-restart state.

### 8.4 Persistence (MySQL, Flyway)

| Table | Key columns |
|---|---|
| `event_log` | `id` BIGINT PK auto, `occurred_at` DATETIME(3), `recorded_at` DATETIME(3), `type`, `entity_kind` (`EQUIPMENT`, `SERVICE`, `ALARM`, `COMMAND` or `SUPERVISOR`), `entity_id`, `equipment_id` NULL, `actor` NULL, `payload` JSON. Indexes on `(occurred_at)` and `(equipment_id, occurred_at)`. **`payload` = the full new state of the entity (§8.1),** plus `schemaVersion` so the format can evolve. |
| `snapshot` | `id`, `taken_at`, `last_event_id`, `board` JSON. Taken every 5 min and at startup. |
| `alarm` | `id`, `equipment_id`, `code`, `instance`, `instance_name`, `severity`, `text`, `source`, `state`, `suppressed`, `raised_at`, `acked_at`, `acked_by`, `ack_comment`, `cleared_at`, plus `open_key` VARCHAR NULL **UNIQUE**. `open_key` is set to `equipment\|code\|instance` while the alarm is open and NULL once it closes, so the database enforces deduplication. |
| `command` | `id`, `equipment_id`, `type`, `reason`, `requested_by`, `requested_at`, `status`, `completed_at`, `detail` |

Retention is 7 days by default and can be configured. A nightly job purges old events and snapshots.

### 8.5 REST API (`/api/v1`)

| Method and path | Purpose |
|---|---|
| `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` | Session login for the SPA |
| `GET /board` | Services (state, `bestCase`), equipment (health, maintenance, role, contact {reachable, lastPollAt, consecutiveFailures}, key values), open alarms, `serverTime`, `lastEventId` |
| `GET /equipment/{id}` | Full detail including tables (sensors, frequencies, interfaces) and the last 50 events |
| `GET /alarms?state=open\|all&severity=&equipment=&from=&to=&page=&size=` | Alarm list |
| `POST /alarms/{id}/ack`, `POST /alarms/ack {ids[], comment?}` | Acknowledge one alarm or many |
| `POST /equipment/{id}/commands {type, reason}` → `202 {commandId}` | Issue a command |
| `GET /commands/{id}` | Command status |
| `POST /equipment/{id}/commands/preview {type}` | Predicted service impact, plus interlock result |
| `GET /stream` (SSE) | Stored events go out as SSE messages `equipment`, `service`, `alarm`, `command` and `supervisor`, each with SSE `id` = `event_log.id`. Not stored, and sent without an id: `telemetry` (one per successful poll, §8.1) and `heartbeat` every 15 s. Supports `Last-Event-ID` resume from an in-memory ring buffer of the last 1000 stored events. If the gap is too large, it sends `resync`, and the client refetches `/board`. |
| `GET /history/board?at=` | Board rebuilt at that instant from the latest snapshot plus an upsert fold of later events. Can be `NO_DATA` (§8.1). |
| `GET /history/events?from=&to=&equipment=&types=&page=` | Event log query |
| `GET /history/export?from=&to=&format=csv\|json` | Incident report: summary header (alarms by severity, commands, services affected) plus the events |

Actuator (`health`, `prometheus`) runs on a separate management port, 8081, which nginx does not proxy.

### 8.6 Security
- **Login:** Spring Security session login through a JSON endpoint. The session cookie is `HttpOnly` and `SameSite=Strict`.
- **CSRF:** a cookie (`XSRF-TOKEN`) that Angular's `HttpClient` handles natively.
- **Users:** `viewer` (read only) and `supervisor` (also acknowledges and sends commands). Their passwords come from the environment.
- **Origin:** the SPA and the API share one origin through nginx, so no CORS configuration is needed.

## 9. UI (Angular 22)

- **Look:** a dark ops-room theme with design tokens in CSS custom properties.
- **Status is never shown by colour alone.** Every health value has a colour, a shape or icon, and a text label:

| Health | Colour | Shape |
|---|---|---|
| OPERATIONAL | green | ✓ |
| DEGRADED | amber | ▲ |
| FAILED | red | ✕ |
| UNKNOWN | magenta, hatched | ? |

- **Maintenance** is shown *on top of* health: a blue outline plus a wrench badge reading `MAINT`. For example, `FDPS-A ✓ MAINT` means the unit is healthy and in maintenance.
- **An UNKNOWN service** shows its lower bound, e.g. `VOICE ? UNKNOWN · at best DEGRADED`.

- **Clock:** a UTC clock is always visible.

```
┌────────────────────────────────────────────────────────────────────────────────┐
│ ATM SUPERVISOR · DEMO ACC        ● LIVE   14:02:37Z        supervisor ▾ [Replay]│
├────────────────────────────────────────────────────────────────────────────────┤
│ SERVICES  [SURV ▲ DEGRADED] [FD ✓ OK] [VOICE ✓ OK] [REC ✓ OK] [NET ✓ OK]        │
├──────────────────────────────────────────────────┬─────────────────────────────┤
│ SURVEILLANCE                                     │ RAD-N · Radar North         │
│  RAD-N ✕   RAD-E ✓   RAD-S ✓   ADSB-GW ✓         │ FAILED since 14:01:52Z      │
│  SDPS-A ✓ ACTIVE ⇄ SDPS-B ✓ STANDBY              │ Channel A ✕  Channel B ✕    │
│ FLIGHT DATA                                      │ Rotation —   Plots/scan —   │
│  FDPS-A ✓ ACTIVE ⇄ FDPS-B ✓ STANDBY              │ Last poll 2 s ago           │
│ VOICE                                            │ [Maintenance on] [Restart]  │
│  VCS-A ✓  VCS-B ✓   RADIO-1 ✓  RADIO-2 ▲         │ Recent events …             │
│ RECORDING  REC ✓       NETWORK  LAN-A ✓ LAN-B ✓  │                             │
├──────────────────────────────────────────────────┴─────────────────────────────┤
│ ALARMS  3 unacknowledged          [Ack selected] [Ack all visible]  filter ▾    │
│ ■ CRIT 14:01:52Z RAD-N   Equipment failed                      UNACK            │
│ ■ MIN  14:01:53Z SDPS-A  Sensor input lost: RAD-N              UNACK            │
│ □ MAJ  13:40:10Z RADIO-2 Main TX failed 132.855 (cleared)      CLEARED-UNACK    │
└────────────────────────────────────────────────────────────────────────────────┘
```

**Routes:**
- `/login`
- `/board` (the default), where the detail panel is deep-linkable at `/board/:equipmentId`
- `/alarms`: full history with filters
- `/replay`
- `/simulator`: the simulator console. It looks clearly different, with a "SIMULATOR – TRAINING ONLY" band, and lists devices, faults and scenarios.

**Live connection:**
- **Client:** an `EventSource` wrapper with reconnect and exponential backoff. On every reconnect it refetches `/board`.
- **Indicator:** `● LIVE` (green), `◌ RECONNECTING` (amber), or `✕ OFFLINE` (red).
- **Stale board:** if the stream has been down for more than 10 s, the whole board gets a hatched "DATA NOT LIVE" overlay.

**Replay mode:**
- **Screen:** the header turns into an amber striped band reading `REPLAY · 13:58:00Z · NOT LIVE`, and every control command is disabled.
- **Timeline:** a timeline with alarm markers, play and pause, speeds of 1×, 4×, 16× and 60×, a step to the next or previous event, *Export incident report*, and *Back to live*.
- **How it works:** replay loads `/history/board?at=T`, then fetches the events in the window and applies them on a client-side timer. Applying an event is the same upsert by entity id that live SSE uses, and the client holds no rules. Seeking reloads the board for the new T, and no server session is needed.
- **Live data can never leak into replay.**
  - **Separate stores:** `ReplayStore` is a separate store that **never subscribes to SSE**. Telemetry handling exists only in `LiveBoardStore`.
  - **One source at a time:** board components read from an injected `BoardSource`. The route chooses it: `LiveBoardStore` on `/board`, `ReplayStore` on `/replay`, never both.
  - **Test:** with replay open, telemetry and events pushed into the live stream change nothing on screen.
- **Replay band:** fixed at the top and cannot be dismissed. It reads `REPLAY · <T> · NOT LIVE · values ≤30 s · no freshness ages`.
- **What it shows:** values in replay are coarser (≤30 s) than they were live. The replay band says so.
- **Gaps:** a `NO_DATA` period shows the "No recorded data: supervisor not running" overlay.

**Commands:** buttons appear only for `supervisor` and only when allowed. Each opens a confirmation dialog that shows the preview (interlock result plus predicted service impact) and requires a reason.

**Alarm sound (phase 3, optional):** a short tone plays on each new critical alarm, with a mute toggle in the header.

**State and tests:**
- **State:** signal-based stores (`BoardStore`, `AlarmStore`, `ReplayStore`).
- **Tests:** Vitest for unit tests and Playwright for end-to-end tests.

## 10. Observability

- **Supervisor metrics** (Micrometer → `/actuator/prometheus`):
  - `atmsup_snmp_requests_total{device,op,outcome}`
  - `atmsup_snmp_request_duration_seconds` (histogram)
  - `atmsup_traps_received_total{type}`
  - `atmsup_alarms_open{severity}`
  - `atmsup_equipment_status{device,status}`
  - `atmsup_sse_clients`
- **Prometheus `snmp_exporter`** polls the simulator devices directly, which shows the standard toolchain next to the project's own manager. Its configuration is fixed as follows:
  - **`deploy/snmp-exporter/generator.yml`:**
    - **Auths:**
      - `atm_v3`: version 3, `security_level: authPriv`, `username: monitor`, `auth_protocol: SHA256`, `priv_protocol: AES`. The passwords are `${SNMP_MONITOR_AUTH}` and `${SNMP_MONITOR_PRIV}`, expanded from the environment by `--config.expand-environment-variables`.
      - `lan_v2c`: version 2, with the community taken from the environment.
    - **Modules:**
      - `atm_techsup` walks `atmTsEquipment` and the per-type groups `atmTsRadar` … `atmTsRecorder`. Status columns become labels through `enum_values` and lookups.
      - `if_mib` reads `ifOperStatus`, `ifAdminStatus` and the octet counters, with `ifDescr` and `ifAlias` as labels.
  - **Generation:** `deploy/snmp-exporter/generate.sh` runs the generator in its container image, using the repo's MIB plus the standard net-snmp MIBs. **The generated `snmp.yml` is committed**, so the demo works offline and never depends on generating it at startup.
  - **Prometheus:** one scrape job per module, with the documented relabelling: `__param_target` set to `simulator:<port>`, `__param_module` and `__param_auth`, and `__address__` set to `snmp-exporter:9116`.
  - **Checks:** CI validates the config with `snmp_exporter --dry-run`. The e2e job asserts that every Prometheus target is `up`, which catches the usual SNMPv3 configuration mistakes.
  - The builder confirms the exact flag names against the snmp_exporter version that is pinned.
- **Grafana** (provisioned, anonymous read-only) has two dashboards:
  - "Supervisor internals": poll latency and timeouts, trap rate, open alarms
  - "Equipment metrics": tracks, plots per scan, queue depth, disk usage
- **Logs:** Spring Boot structured logging (ECS JSON) to stdout.

## 11. Testing

| Level | What |
|---|---|
| Unit (JUnit 5, AssertJ, Mockito) | Service rules (table-driven, including both UNKNOWN passes and the "known failure is not hidden" cases), status derivation and freshness, switch health from `ifTable`, the alarm lifecycle state machine (including identity by instance and severity escalation re-unacknowledging), suppression, command interlocks and impact preview, the board writer pipeline (derived events commit with their cause), `BoardReducer`, replay reconstruction and `NO_DATA` detection, SSE ring buffer |
| Replay fidelity | Property-style test: run a scripted input sequence through the board writer, then check that the live board equals `history/board?at=now` and equals the fold of every event in `id` order. A second test runs the lost-contact sequence from §8.1 and checks the board at each T in that example's table. |
| Alarm resync | Every row of the reconciliation table in §8.2, the partial-walk case (no changes), and the switch `ifTable` variant. The `TrapLossResyncIT` integration test injects `DROP_TRAPS`, then a disk alarm, then restores contact, and expects exactly one `ALARM_RAISED via RESYNC`. |
| Commands | Interlocks; preview equal to the observed outcome for every scenario command (`CommandPreviewConsistencyIT`); every row of the SET-outcome table, driven by agent faults that return each error status (`SetErrorHandlingIT`); restart verification, including that no pre-restart state is shown (`RestartVerificationIT`) |
| SNMP compliance | The hand-written agent is exercised by three independent clients: the SNMP4J manager in the integration tests, net-snmp `snmpget`, `snmpbulkwalk` and `snmpset` in a CI step, and `snmp_exporter`. There is also an engine-boots test: restart a device, then check that its next v3 trap is accepted. |
| SNMP integration | The simulator publishes a test-fixture artifact that starts real agents in-process on random UDP ports. Supervisor tests then cover: GET, GETBULK and SET; v3 authPriv success and a wrong-password report; timeout leading to lost contact; trap and inform reception; and alarm-table resync after `DROP_TRAPS`. |
| Persistence | Testcontainers MySQL 8.4: Flyway migrations, `open_key` uniqueness, snapshot-plus-events replay queries, retention purge |
| Architecture | `ApplicationModules.verify()` (Spring Modulith) |
| API | MockMvc/RestTestClient slice tests, including security (viewer gets 403 on commands) and `ProblemDetail` errors |
| Frontend | Vitest: stores, the SSE wrapper (reconnect and resync), status presentation (never green when UNKNOWN), and the replay timer |
| End-to-end (Playwright, against Compose) | Log in → board renders → run `radar-outage` through the simulator API → the alarm appears within 10 s → acknowledge it → open replay and see the board as it was before the fault |
| MIB | `smilint -l 3` in CI |

Coverage is reported with JaCoCo but not gated.

## 12. Repository, build, delivery

```
atm-supervisor/
├── pom.xml                 parent (Java 25, dependency management)
├── mib/                    DEMO-ATM-TECHSUP-MIB.txt + Java OID constants (Maven module)
├── simulator/              Spring Boot + hand-written agents on SNMP4J core; scenarios in resources
├── supervisor/             Spring Boot modular monolith
├── ui/                     Angular 22 workspace; Dockerfile (build → nginx)
├── config/inventory.yml    single device inventory shared by simulator and supervisor
├── deploy/
│   ├── docker-compose.yml
│   ├── nginx/              SPA + /api → supervisor:8080, /sim/api → simulator:8090 (SSE: proxy_buffering off)
│   ├── prometheus/
│   ├── snmp-exporter/      generator.yml + generated snmp.yml
│   └── grafana/            provisioning + dashboards
├── docs/
├── .github/workflows/      ci.yml, dependabot.yml
├── .env.example
├── LICENSE                 Apache-2.0
└── README.md
```

**Docker Compose** runs `mysql` (with a healthcheck), `simulator`, `supervisor` (which waits for MySQL to be healthy), `ui`, `prometheus`, `snmp-exporter` and `grafana`. Named volumes: `mysql-data`, and `simulator-data` for the persisted engine boots (§6.1).

Host ports:

| Port | Service |
|---|---|
| `8080` | UI (nginx) |
| `3000` | Grafana |
| `9090` | Prometheus |
| `16101–16115/udp` | The agents, published for `snmpwalk` in the demo |

**CI** (GitHub Actions on push and PR) has these jobs:
- `backend`: Temurin 25, `mvn -B verify`, including Testcontainers
- `frontend`: Node 26 LTS; lint, unit tests and build
- `mib-lint`: `smilint`
- `e2e`: Compose build plus Playwright, run on the main branch and on PRs labelled `e2e`
- `images`: build images, and push them to GHCR on tags

Dependabot covers Maven, npm and Actions.

## 13. Phases

Each phase ends with a tag, and the repo is presentable at every tag.

| Phase | Tag | Scope |
|---|---|---|
| 1 "First slice" | `v0.1` | **Three device types only: radar, SDPS, FDPS (7 devices).** Covers the full MIB file (all groups defined), the `mib` module, the hand-written agent (scalars, the SDPS sensor table and the alarm table) with persisted engine boots, and the faults API. In the supervisor: inventory, snmp, polling, traps and informs, the board writer, the `ServiceEvaluator` for `SURV` and `FD`, alarms with acknowledge (no resync yet) and the event log. Also REST plus SSE, the Angular board with alarm list and detail panel, Compose (mysql, simulator, supervisor, ui), **basic CI** (`mvn verify`, `ng test` and build, `smilint`), unit and SNMP integration tests, and a minimal README. |
| 2 "The whole centre" | `v0.2` | The remaining types: ADS-B, VCS, radio, recorder, and the switches (IF-MIB, v2c), bringing the total to 15 devices and all five services. Also alarm resync, lost-contact UI, scenarios and the simulator console. |
| 3 "It controls and remembers" | `v0.3` | Control commands with interlocks, preview, SET outcome handling and verification. Security and roles, snapshots, and the replay UI with export. |
| 4 "Production-minded" | `v1.0` | Prometheus, snmp_exporter and Grafana, Playwright end-to-end tests, net-snmp CLI checks in CI, full docs (§15), README with GIF, alarm sound |

Phases are split by **device type**, not by device count, because the work grows with the number of types. The three v0.1 types already cover scalars, a table, an A/B pair with automatic switchover, traps and informs. v0.1 is therefore a credible demo on its own: "radar fails, the tracker loses a sensor, FDPS fails over".

## 14. Risks and mitigations

| Risk | Mitigation |
|---|---|
| The hand-written agent misses protocol corner cases (GETBULK limits, error statuses, v3 reports) | Day-one spike: one device with a scalar, a table, v3 authPriv GET, GETBULK and SET, and an INFORM to an SNMP4J manager. Check it straight away with net-snmp `snmpbulkwalk` and `snmpset`. Three independent clients test it continuously (§11). |
| v3 traps silently dropped after a simulator restart | Persisted, ever-growing `snmpEngineBoots` (§6.1), plus the engine-boots test |
| Phase 1 grows too big | v0.1 is limited to three device types (§13). Every other type is a configuration plus one model class on top of the same agent. |
| Spring Boot 4.1 compatibility with Modulith or springdoc | Check the versions when planning. Modulith is optional: plain packages plus an ArchUnit test would replace it. |
| v3 trap reception across multiple engines | Fixed engine IDs in the inventory, with keys localised per engine at startup |
| UDP behaviour in Docker Desktop on macOS | Container-to-container traffic is fine. Test the published UDP ports from the host early. |
| Time | The phases. v0.1 alone is already a credible demo. |

## 15. Documentation deliverables (English)

- **`README.md`:**
  - a one-paragraph pitch and a GIF
  - a "What this demonstrates" table (SNMP, REST, full stack, ATM domain, safety)
  - the quickstart, a Mermaid architecture diagram and the tech stack
  - a disclaimer that the centre is fictional and the project is not affiliated with any ANSP
- **`docs/architecture.md`:** C4 context, container and component views (Mermaid), plus the Modulith-generated module diagrams
- **`docs/domain.md`:** an ATM/CNS primer covering each simulated system, redundancy, service rules, and a glossary (ACC, CNS, MSSR, Mode S, ADS-B, SDPS, FDPS, AFTN, OLDI, VCS, degraded mode, System Control)
- **`docs/mib.md`:** a guided tour of the MIB, with `snmpwalk` and `snmpset` examples against the running demo
- **`docs/adr/`:**
  - 0001 modular monolith rather than microservices
  - 0002 SNMPv3 authPriv by default, v2c for legacy equipment
  - 0003 traps as hints, polling as the source of truth, resync
  - 0004 event log of full-state facts, snapshots and an upsert reducer for replay (no outbox, and why)
  - 0005 SSE rather than WebSocket
  - 0006 a single shared inventory file
  - 0007 supervisor HA left as future work
- **`docs/safety/hazard-log.md`:** each hazard has a cause, a mitigation, and verification. The tests below are the planned names; the plan creates them, and the log links to them.

  | Hazard | Mitigation | Verified by |
  |---|---|---|
  | H1 Stale data shown as current | Freshness age; `UNKNOWN` after 3 failed polls; never green when `UNKNOWN`; the "DATA NOT LIVE" overlay when the stream is down; "unconfirmed" marker on alarms | `StatusDeriverTest`, `ServiceEvaluatorTest` (UNKNOWN passes), `status-badge.spec.ts`, `live-connection.spec.ts`, e2e `lost-contact` |
  | H2 Replay mistaken for live | Separate `ReplayStore` with no SSE; fixed amber band; commands disabled in replay; `NO_DATA` for gaps | `replay-isolation.spec.ts`, `replay-band.spec.ts`, `ReplayNoDataTest`, e2e replay step |
  | H3 Unintended or unsafe control command | Role check; mandatory reason; interlocks; preview through the same `ServiceEvaluator`; confirmation dialog; idempotent `becomeActive`; SETs with 0 retries | `CommandInterlockTest`, `CommandSecurityTest`, `CommandPreviewConsistencyIT`, `SetErrorHandlingIT` |
  | H4 An alarm flood hides a critical alarm | Ordered by severity, then time; maintenance suppression; escalation goes back to unacknowledged; critical tone | `AlarmLifecycleTest`, `alarm-list.spec.ts` |
  | H5 A lost trap hides a failure | Polling is the source of truth; INFORMs for critical alarms; resync reconciliation | `AlarmResyncTest`, `TrapLossResyncIT` |
  | H6 A failed SNMP authentication goes unnoticed | Alarm 902 on USM reports; a Grafana panel | `SnmpV3AuthFailureIT` |
  | H7 A restarted device shown with its pre-restart state | `UNKNOWN (RESTARTING)` until the first poll; restart proven through `sysUpTime` | `RestartVerificationIT` |
  | H8 v3 traps silently rejected after an agent restart | Persisted, ever-growing `snmpEngineBoots` | `EngineBootsPersistenceIT` |
- **`docs/safety/CR-001-auto-switchover-visibility.md`:** a sample change request, written the way such a team would write one: description, impact analysis, safety assessment, effort estimate, and test evidence.
- **`docs/demo-script.md`:** a 7-minute guided demo:
  1. context
  2. board tour and `snmpwalk`
  3. `radar-outage`
  4. `fdps-failover` with the interlock
  5. `lost-contact` and resync
  6. replay and export
  7. Grafana and a quick code tour (MIB, reducer, hazard log)
- **`docs/operator-guide.md`:** a short guide for the "System Controller" user

## 16. Left to the builder

- **Code details:** exact class names and package layout inside each module.
- **Visual details:** CSS details within the stated design tokens and status encoding.
- **Simulator values:** noise and dynamics functions and nominal values, within the ranges given.
- **Alarm thresholds and catalogue:** threshold values can be tuned, and the catalogue can grow, with the starter values as defaults.
- **OID constant sync:** how to keep the MIB and the Java constants in sync (§6).
- **Library choice:** whether to use Spring Modulith or the ArchUnit fallback, if compatibility forces it (§14).
