# User and Operator Guide

This manual is a practical, hands-on guide to starting, reading, operating, and troubleshooting **ATM Supervisor** (`v0.1.1`). It covers every part of the operator screen, step-by-step operational playbooks, the supervisor and simulator REST APIs, direct SNMPv3 commands, and common troubleshooting checks.

For domain background on technical supervision in an Area Control Centre, see the [domain primer](domain.md). For a scripted tour of v0.1, see the [demo walkthrough](demo-v0.1.md).

---

## 1. Starting, Resetting, and Stopping the Stack

### 1.1 First-Time Build (Online)

From the repository root, build the container images, start the stack, reset the simulator, and wait until all 7 devices report `OPERATIONAL`:

```sh
scripts/demo-preflight.sh --build
```

When the stack is ready, the script prints:

```text
READY  7/7 OPERATIONAL  SURV OK  FD OK  http://localhost:8080
```

### 1.2 Everyday Start and Clean Reset (Offline)

Once the images are built, the stack needs no internet connection:

```sh
# Start (or reuse) the running stack and reset all simulator faults
scripts/demo-preflight.sh

# Wipe all stored alarms, event history, and engine boot counters first
scripts/demo-preflight.sh --fresh
```

You can also manage the stack directly with Docker Compose from the repository root:

```sh
docker compose up -d --wait
docker compose down          # stop containers, keep the database & engine-boots volumes
docker compose down -v       # stop containers and delete volumes
```

Open **<http://localhost:8080>** in your browser.

### 1.3 Signing In

The board opens with the sign-in screen. In demo mode it lists three accounts; select one to fill in the form:

| Account | Role | What it can do |
|---|---|---|
| `admin1` | ADMIN | Everything a supervisor can |
| `supervisor1` | SUPERVISOR | View the board and acknowledge alarms |
| `demo1` | VIEWER | View the board |

Each password is the account name followed by `-demo`. **Create a read-only account** registers a new VIEWER. When a
session ends (after 12 hours without the board open, or when the supervisor restarts), the board returns to the
sign-in screen with the notice *Your session ended. Sign in again.* After **Sign out**, another window that still
shows the board follows within 30 minutes, when its live stream reconnects. **Sign out** is at the right of the header.

---

## 2. Screen Tour: Reading the Operator Board

The operator console is a full-viewport grid divided into four regions: the **Header Bar**, the **Services Strip**, the **Equipment Board & Detail Panel**, and the **Alarms Pane**.

![Nominal board: all 7 devices operational and both services OK](images/board-nominal.png)

### 2.1 Top Header Bar (`ATM SUPERVISOR · DEMO ACC`)

* **Connection Status Indicator:**
  * `● LIVE` (green) — Connected to the Server-Sent Events stream (`/api/v1/stream`).
  * `◌ RECONNECTING` (amber) — Stream interrupted; the browser is reconnecting and will automatically replay missed events (via `Last-Event-ID`) or refetch `/api/v1/board`.
  * `✕ OFFLINE` (red) — Disconnected.
* **UTC Clock:** Displays server-synchronised UTC time with a `Z` suffix (for example `15:24:18Z`).
* **`DATA NOT LIVE` Safety Overlay:** If the SSE stream remains down for more than **10 seconds**, a diagonal hatched overlay covers the board so an operator cannot mistake a frozen browser tab for live supervision.

### 2.2 Operational Services Strip (`SURV` and `FD`)

Immediately below the header sits the services strip:

* **`SURV` (Surveillance):** Summarises whether controllers have a working multi-sensor tracker (`SDPS-A/B`) fed by at least two radars (`RAD-N/E/S`).
* **`FD` (Flight Data):** Summarises whether controllers have a working flight data processor (`FDPS-A/B`) with standby redundancy.
* **Hover Tooltip (`title`):** Hovering over a service chip shows the exact reasons behind its current status (for example, `"Fewer than 2 radars available; Only one SDPS unit available"` or `"Unconfirmed: Only one SDPS unit available (equipment in UNKNOWN state: SDPS-B)"`).

### 2.3 Status Badges (Colour + Symbol + Text)

Status is never communicated by colour alone:

| Badge | Symbol | Colour | Meaning |
|---|---|---|---|
| **`✓ OPERATIONAL`** / **`✓ OK`** | `✓` | Green | Confirmed healthy. |
| **`▲ DEGRADED`** | `▲` | Amber | Working with reduced capability (e.g. one radar channel failed, or fewer than 2 radars available) or without standby redundancy (down to one SDPS or FDPS unit). |
| **`✕ FAILED`** | `✕` | Red | Confirmed unable to perform its operational function. |
| **`? UNKNOWN`** | `?` | Violet (hatched) | The supervisor cannot confirm the current state. On a service chip, it also shows the optimistic upper bound: **`? UNKNOWN · at best OK`** or **`? UNKNOWN · at best DEGRADED`**. |

### 2.4 Equipment Tiles (`SURVEILLANCE` and `FLIGHT DATA`)

![Board during a failure: RAD-N failed, SDPS-A unknown, FDPS switched over to B](images/board-failure-and-unknown.png)

Each equipment tile (`RAD-N`, `RAD-E`, `RAD-S`, `SDPS-A`, `SDPS-B`, `FDPS-A`, `FDPS-B`) displays:

1. **Identifier & Pair Role:** The top row shows the device ID (`SDPS-A`) and, for redundant pairs, its current role (`ACTIVE` or `STANDBY`).
2. **Health Badge & `MAINT` Tag:**
   * Shows the health badge (`✓ OPERATIONAL`, `▲ DEGRADED`, `✕ FAILED`, or `? UNKNOWN`).
   * If `atmTsEqMaintenanceMode` is enabled on the device, a blue **`MAINT`** badge is shown *next to* its real hardware health.
3. **`UNKNOWN` Reason & Last Contact Note:**
   * When a device is `? UNKNOWN`, the tile becomes diagonally hatched and shows why:
     * `last contact <HH:MM:SSZ>` — when `LOST_CONTACT` (after 3 failed lightweight probes, ~15 s).
     * `awaiting status` — contact just returned on a probe, waiting for the immediate verification full poll.
     * `status unavailable` — lightweight probes succeed, but full polls have failed for `> 3×` the poll interval.
     * `not yet polled` — before the first poll on a fresh start.
     * `supervisor restarted` — after a supervisor restart before the first poll completes.
   * While `UNKNOWN`, the tile's numerical summary values (`Plots/scan`, `Rotation ms`, `Tracks`, `Latency ms`, `Plans`, `Queue`) are **dimmed (`40%` opacity)** so they are never read as current measurements.
4. **Amber Stale-Poll Age (`N s ago`):**
   * Under normal polling (every 5 s), tiles stay uncluttered. If more than **2× the poll interval (> 10 s)** passes since `lastPollAt` before the device reaches `LOST_CONTACT`, an amber **`11 s ago`** warning appears on the tile.

### 2.5 Equipment Detail Panel (Right-Hand Drawer)

Click any equipment tile to select it (deep-linked at `/board/:equipmentId`, for example <http://localhost:8080/board/SDPS-A>):

* **Header & State Duration:** Shows the full name (`Surveillance Data Processing A SDPS-A`), health badge, `since <HH:MM:SSZ>`, and any `UNKNOWN` reason label.
* **Role & Maintenance:** Shows `Role` (`ACTIVE`, `STANDBY`, `STANDALONE`) and `Maintenance` (`MAINT` or `no`).
* **Device Values & Sub-Component Tables:**
  * If the device is `UNKNOWN`, a **`Last known values`** heading appears and the values are dimmed.
  * **Radar (`RAD-N/E/S`):** `Rotation (ms)`, `Plots/scan`, `Active channel` (`CHANNEL_A` / `CHANNEL_B`), `Channel A` status, and `Channel B` status.
  * **SDPS (`SDPS-A/B`):** `Tracks`, `Latency (ms)`, plus the **`Sensor / Input` table** listing each radar (`RAD-N`, `RAD-E`, `RAD-S`) as `RECEIVING`, `LOST`, or `EXCLUDED`.
  * **FDPS (`FDPS-A/B`):** `Flight plans`, `Queue depth`, and `Latency (ms)`.
* **Poll Freshness (`Last poll N s ago`):** Ticks every second against server time.
* **Recent Events:** Lists up to the last **50 durable events** recorded in PostgreSQL for this device (`EQUIPMENT_STATE_CHANGED`, `CONTACT_LOST`, `CONTACT_RESTORED`, `ALARM_RAISED`, `ALARM_CLEARED`, etc.) with their UTC timestamps and human-readable summaries.

### 2.6 Alarms Pane (Bottom Bar & Table)

The bottom pane tracks every open alarm (`ACTIVE_UNACK`, `ACTIVE_ACK`, `CLEARED_UNACK`):

* **Ordering:** Rows are sorted automatically by **severity** (`CRIT` $\rightarrow$ `MAJ` $\rightarrow$ `MIN` $\rightarrow$ `WARN`) and then by **raise time** (newest first).
* **Columns:**
  * Checkbox (for selecting individual alarms)
  * `Sev` (`CRIT`, `MAJ`, `MIN`, `WARN`)
  * `Time` (UTC timestamp when the alarm was raised)
  * `Equipment` (`RAD-N`, `SDPS-A`, or service ID `SURV` / `FD` for supervisor alarms `903`/`904`)
  * `Alarm` (description, instance name such as `· RAD-N`, and a violet **`?`** badge if the equipment is currently `UNKNOWN` and its alarms are therefore **unconfirmed**)
  * `State` (`UNACK`, `ACK`, or `CLEARED-UNACK`)
* **Controls:**
  * **`Show suppressed (N)` checkbox:** Reveals alarms raised by devices that are in `MAINT` mode (hidden by default so planned work does not flood the watch).
  * **`Ack selected` button:** Acknowledges the checked rows.
  * **`Ack all visible` button:** Acknowledges every `UNACK` and `CLEARED-UNACK` alarm currently visible in the table.

---

## 3. Hands-On Operational Playbooks

You can run these five workflows against the live stack to see how the board responds to faults, acknowledgements, switchovers, lost contact, and maintenance.

### Playbook 1: Degrade a Radar Channel, Fail the Radar, and Acknowledge Alarms

1. **Inject a single-channel fault on `RAD-N`:**
   ```sh
   curl -s -X POST localhost:8080/sim/api/devices/RAD-N/faults \
     -H 'Content-Type: application/json' -d '{"fault":"CHANNEL_A_FAIL"}'
   ```
   * **What you see:** `RAD-N` turns **`▲ DEGRADED`**. Click the `RAD-N` tile: the detail panel shows `Active channel: CHANNEL_B`, `Channel A: FAILED`, `Channel B: OPERATIONAL`. Two `MIN` alarms appear: `102 Equipment degraded` and `201 Channel A failed`. Because `RAD-N` is still producing plots on Channel B, `SDPS-A/B` still show `RAD-N: RECEIVING` and `SURV` stays **`✓ OK`**.
2. **Fail `RAD-N` completely:**
   ```sh
   curl -s -X POST localhost:8080/sim/api/devices/RAD-N/faults \
     -H 'Content-Type: application/json' -d '{"fault":"FAIL"}'
   ```
   * **What you see:** `RAD-N` turns **`✕ FAILED`** with `CRIT` alarm `101 Equipment failed`, and `201`/`202` escalate to `MAJ`. Both `SDPS-A` and `SDPS-B` raise `MIN` alarm `401 Sensor input lost · RAD-N`. `SURV` *still* stays **`✓ OK`** because 2 radars (`RAD-E` and `RAD-S`) remain available.
3. **Acknowledge the alarms:**
   * Click **Ack all visible** in the alarm bar. Every row changes from `UNACK` to `ACK` and `0 unacknowledged` is shown, while the rows remain visible because the faults are still active.
4. **Recover `RAD-N`:**
   ```sh
   curl -s -X POST localhost:8080/sim/api/devices/RAD-N/faults \
     -H 'Content-Type: application/json' -d '{"fault":"RECOVER"}'
   ```
   * **What you see:** Because you already acknowledged the alarms, they transition directly from `ACK` to `CLOSED` and disappear from the open alarm list as soon as `RAD-N` recovers. (Any alarm you did *not* acknowledge transitions to `CLEARED-UNACK` and waits for your acknowledgement before closing.)

---

### Playbook 2: Observe an Automatic 3-Second A/B Pair Switchover

1. **Fail the active Flight Data Processor (`FDPS-A`):**
   ```sh
   curl -s -X POST localhost:8080/sim/api/devices/FDPS-A/faults \
     -H 'Content-Type: application/json' -d '{"fault":"FAIL"}'
   ```
2. **Watch the two-step transition on the board:**
   * **Immediately ($t = 0\text{ s}$):** `FDPS-A` turns **`✕ FAILED`** (`101 Equipment failed`), `FD` turns **`▲ DEGRADED`** (`Only one FDPS unit available`), and supervisor alarm **`903 Service redundancy lost: Flight data`** (`MAJ`) is raised.
   * **After 3 seconds ($t \approx 3\text{–}4\text{ s}$):** The pair switches over automatically: `FDPS-B` becomes **`ACTIVE`**, `FDPS-A` becomes **`STANDBY`** (still `✕ FAILED`), and `FDPS-B` raises `WARN` alarm **`104 Switchover occurred`**. `FD` remains **`▲ DEGRADED`** because the pair has no healthy standby left.

---

### Playbook 3: Lost Contact (`UNREACHABLE`) and Fast Probe Recovery

1. **Cut SNMP communication to `SDPS-B`:**
   ```sh
   curl -s -X POST localhost:8080/sim/api/devices/SDPS-B/faults \
     -H 'Content-Type: application/json' -d '{"fault":"UNREACHABLE"}'
   ```
2. **Observe the progression:**
   * **At ~10 s:** An amber poll age (`11 s ago`) appears on the `SDPS-B` tile.
   * **At ~15 s (3 failed probes):** `SDPS-B` turns **`? UNKNOWN`** (`last contact <UTC>`) with dimmed values, and raises `MAJ` alarm **`901 Lost contact`** with the unconfirmed **`?`** marker.
   * Look at the `SURV` chip in the services strip: it turns **`? UNKNOWN · at best OK`** because whether surveillance still has full SDPS redundancy depends on the unreachable `SDPS-B`.
3. **Restore communication:**
   ```sh
   curl -s -X POST localhost:8080/sim/api/devices/SDPS-B/faults \
     -H 'Content-Type: application/json' -d '{"fault":"RECOVER"}'
   ```
   * Within one probe interval (≤ 5 s), `SDPS-B`'s lightweight `sysUpTime.0` probe succeeds, moves the device's circuit breaker to `HALF_OPEN`, triggers an immediate verification full poll, and restores `SDPS-B` to **`✓ OPERATIONAL`** and `SURV` to **`✓ OK`**.

---

### Playbook 4: Maintenance Mode, Alarm Suppression, and SNMP `SET` Commands

In `v0.1.1`, you can exercise the agent's writable MIB objects (`atmTsEqMaintenanceMode` and `atmTsEqCommand`) using `snmpset` with the `operator` SNMPv3 user. (Run via a throwaway Alpine container if your host `net-snmp` is older than 5.8.)

1. **Put `FDPS-B` (`STANDBY`) into Maintenance Mode (`true(1)`):**
   ```sh
   docker run --rm --network atm-supervisor_default -v "$PWD/mib":/mib:ro alpine:3.22 sh -c '
     apk add -q net-snmp-tools &&
     snmpset -v3 -l authPriv -u operator -a SHA-256 -A operator-auth-demo -x AES -X operator-priv-demo \
       -M +/mib:/usr/share/snmp/mibs -m +DEMO-ATM-TECHSUP-MIB \
       simulator:16107 DEMO-ATM-TECHSUP-MIB::atmTsEqMaintenanceMode.0 i 1'
   ```
   * **What you see on the board:**
     * `FDPS-B` keeps its **`✓ OPERATIONAL`** badge and gains the blue **`MAINT`** badge beside it.
     * Because a unit in maintenance counts as unavailable (a planned outage), `FD` turns **`▲ DEGRADED`** (`Only one FDPS unit available`) and raises alarm `903`.
     * Alarm `103 Maintenance mode on` is raised on `FDPS-B`, but because `FDPS-B` is in maintenance, it is **suppressed**! Check **`Show suppressed (1)`** in the alarm bar to see it.
2. **Restart `FDPS-B` while in Maintenance (`restart(3)`):**
   ```sh
   docker run --rm --network atm-supervisor_default -v "$PWD/mib":/mib:ro alpine:3.22 sh -c '
     apk add -q net-snmp-tools &&
     snmpset -v3 -l authPriv -u operator -a SHA-256 -A operator-auth-demo -x AES -X operator-priv-demo \
       -M +/mib:/usr/share/snmp/mibs -m +DEMO-ATM-TECHSUP-MIB \
       simulator:16107 DEMO-ATM-TECHSUP-MIB::atmTsEqCommand.0 i 3'
   ```
   * *(Note: If you send `restart(3)` while `atmTsEqMaintenanceMode` is `false(2)`, the agent rejects it with `inconsistentValue`.)*
3. **Take `FDPS-B` out of Maintenance (`false(2)`), then Command a Manual Switchover (`becomeActive(2)`):**
   ```sh
   docker run --rm --network atm-supervisor_default -v "$PWD/mib":/mib:ro alpine:3.22 sh -c '
     apk add -q net-snmp-tools &&
     snmpset -v3 -l authPriv -u operator -a SHA-256 -A operator-auth-demo -x AES -X operator-priv-demo \
       -M +/mib:/usr/share/snmp/mibs -m +DEMO-ATM-TECHSUP-MIB \
       simulator:16107 DEMO-ATM-TECHSUP-MIB::atmTsEqMaintenanceMode.0 i 2 &&
     snmpset -v3 -l authPriv -u operator -a SHA-256 -A operator-auth-demo -x AES -X operator-priv-demo \
       -M +/mib:/usr/share/snmp/mibs -m +DEMO-ATM-TECHSUP-MIB \
       simulator:16107 DEMO-ATM-TECHSUP-MIB::atmTsEqCommand.0 i 2'
   ```
   * **What you see:** `MAINT` disappears, `FD` returns to **`✓ OK`**, and `FDPS-B` swaps roles with `FDPS-A` to become **`ACTIVE`**.

---

### Playbook 5: Verify Full-Poll Alarm Reconciliation (`via RESYNC`) When Traps Are Lost

1. **Tell `RAD-S` to drop all outgoing traps and informs, then fail it:**
   ```sh
   curl -s -X POST localhost:8080/sim/api/devices/RAD-S/faults \
     -H 'Content-Type: application/json' -d '{"fault":"DROP_TRAPS"}'
   curl -s -X POST localhost:8080/sim/api/devices/RAD-S/faults \
     -H 'Content-Type: application/json' -d '{"fault":"FAIL"}'
   ```
2. **What you see:** Within at most one 5-second poll interval, `RAD-S` turns **`✕ FAILED`** and alarms `101`, `201`, and `202` appear in the alarm list even though zero traps were sent. Click the `RAD-S` tile: **Recent events** in the detail panel lists the `ALARM_RAISED` events. The event log records each of them with `via: RESYNC` in its payload, because they came from the full poll's alarm-table walk; the board itself does not display the source.

---

## 4. REST API Reference

When the Compose stack is running, nginx on `localhost:8080` serves both the supervisor API (`/api/v1/*`) and the simulator fault API (`/sim/api/*`).

### 4.1 Supervisor API (`/api/v1`)

| Method & Path | Description & Query Parameters | Example |
|---|---|---|
| `GET /api/v1/board` | Returns the full live board snapshot (`centre`, `serverTime`, `lastEventId`, `services`, `equipment`, open `alarms`). | `curl -s localhost:8080/api/v1/board \| python3 -m json.tool` |
| `GET /api/v1/equipment/{id}` | Returns the equipment state plus its last 50 events (`recentEvents`). Returns `404` for an unknown ID. | `curl -s localhost:8080/api/v1/equipment/RAD-N \| python3 -m json.tool` |
| `GET /api/v1/alarms` | Searches alarms. Params: `state` (`open` \| `all`, default `open`), `severity` (`CRITICAL`, `MAJOR`, `MINOR`, `WARNING`), `equipment`, `from` (ISO-8601 UTC), `to`, `page` (`0`), `size` (`1..500`, default `50`). | `curl -s 'localhost:8080/api/v1/alarms?state=all&equipment=RAD-N'` |
| `POST /api/v1/alarms/{id}/ack` | Acknowledges one alarm. Optional body: `{"comment":"noted"}`. | `curl -s -X POST localhost:8080/api/v1/alarms/<id>/ack -H 'Content-Type: application/json' -d '{"comment":"checked"}'` |
| `POST /api/v1/alarms/ack` | Bulk-acknowledges multiple alarms. Body: `{"ids":["..."],"comment":"..."}`. | `curl -s -X POST localhost:8080/api/v1/alarms/ack -H 'Content-Type: application/json' -d '{"ids":["id1","id2"]}'` |
| `GET /api/v1/stream` | Server-Sent Events stream (`equipment`, `service`, `alarm`, `supervisor` with `id`, plus volatile `telemetry` and `heartbeat`). Accepts `Last-Event-ID` header. | `curl -N localhost:8080/api/v1/stream` |

### 4.2 Simulator Fault Injection API (`/sim/api`)

| Method & Path | Description | Example |
|---|---|---|
| `GET /sim/api/devices` | Lists all simulated devices, their UDP ports, roles, maintenance flags, and currently active faults. | `curl -s localhost:8080/sim/api/devices \| python3 -m json.tool` |
| `GET /sim/api/devices/{id}` | Inspects a single simulated device's internal state. | `curl -s localhost:8080/sim/api/devices/SDPS-A` |
| `POST /sim/api/devices/{id}/faults` | Applies a fault (`FAIL`, `DEGRADE`, `RECOVER`, `CHANNEL_A_FAIL`, `CHANNEL_B_FAIL`, `SENSOR_LOST` with `target`, `UNREACHABLE`, `DROP_TRAPS`). | `curl -s -X POST localhost:8080/sim/api/devices/SDPS-A/faults -H 'Content-Type: application/json' -d '{"fault":"SENSOR_LOST","target":"RAD-N"}'` |
| `POST /sim/api/reset` | Clears all faults on all devices and restores default active/standby roles (`204 No Content`). | `curl -X POST localhost:8080/sim/api/reset` |

---

## 5. Health Checks, Metrics & Troubleshooting

### 5.1 Querying Supervisor Actuator Metrics (`:8081`)

Inside the `supervisor` container, Spring Boot Actuator runs on loopback port `8081`. You can inspect health and Micrometer metrics directly via Docker Compose:

```sh
# Check supervisor health
docker compose exec supervisor bash -c \
  'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && cat <&3'

# Check connected SSE clients, circuit breaker states, or stale poll discards
docker compose exec supervisor bash -c \
  'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/metrics/resilience4j.circuitbreaker.state HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && cat <&3'
```

Useful metric names under `/actuator/metrics/<name>`:
* `resilience4j.circuitbreaker.state` — Per-device circuit breaker state (`closed`, `open`, `half_open`).
* `atmsup.poll.rejected` — Full polls skipped because the device's circuit breaker was `OPEN`.
* `atmsup.poll.coalesced` — Overlapping full polls coalesced by single-flight scheduling.
* `atmsup.board.stale.results` — In-flight probe or poll results discarded because their generation (`contactGeneration` or `statusGeneration`) was outdated.
* `atmsup.sse.clients` / `atmsup.sse.disconnects` — Active SSE streams and overflow/error disconnections.

### 5.2 Common Troubleshooting Questions

| Symptom | Cause | Solution |
|---|---|---|
| `Port 8080 is in use by another process.` during preflight | Another local server or container is bound to TCP `8080`. | Run `lsof -nP -iTCP:8080 -sTCP:LISTEN` to identify and stop the conflicting process. |
| `Invalid authentication protocol specified after -a flag: SHA-256` when running `snmpget` on macOS | The system `/usr/bin/snmpget` shipped with macOS is net-snmp 5.6, which lacks `SHA-256`. | Install Homebrew net-snmp (`brew install net-snmp` and use `$(brew --prefix net-snmp)/bin/snmpget`) or run the Alpine container command from [Playbook 4](#playbook-4-maintenance-mode-alarm-suppression-and-snmp-set-commands). |
| Old `CLEARED-UNACK` alarms remain in the alarm list after `POST /sim/api/reset` | By design: an unacknowledged alarm stays in `CLEARED-UNACK` after the fault clears so fleeting faults are never lost. | Click **Ack all visible** in the UI to close them, or run `scripts/demo-preflight.sh --fresh` to wipe the database volume. |
| A device stays `UNKNOWN` after recovering from `UNREACHABLE` | If contact returned while a `HALF_OPEN` trial poll was already in flight, verification can wait for the breaker's 30 s timeout. The device stays safely `UNKNOWN` meanwhile. | No action needed: it recovers within about 30 s. |
