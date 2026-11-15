# Operator Guide (System Controller)

This guide explains how to read and operate the **ATM Supervisor** board (<http://localhost:8080>) as implemented in **v0.1.1**, and notes which controls arrive in `v0.2` and `v0.3`. For the domain background, see the [domain primer](domain.md); for a step-by-step live tour, see the [demo walkthrough](demo-v0.1.md).

---

## 1. Screen Layout

The operator board is arranged around four areas:

1. **Top Header Bar:**
   * Shows the centre title (`ATM SUPERVISOR · DEMO ACC`), the live stream indicator (`LIVE` when connected to `/api/v1/stream`, `RECONNECTING` or `OFFLINE` otherwise), and the current **UTC time (`Z`)**.
   * If the SSE stream has been down for more than 10 seconds, a hatched **"DATA NOT LIVE"** overlay covers the board so stale screen state cannot be trusted as live ([Hazard H1](safety/hazard-log.md#h1-stale-observations-shown-as-current)).
2. **Operational Services Banner (`SERVICES`):**
   * Shows the derived status of the centre's operational services:
     * **`SURV` (Surveillance):** Derived from `SDPS-A`, `SDPS-B`, `RAD-N`, `RAD-E`, and `RAD-S` ([`SurveillanceRule`]).
     * **`FD` (Flight Data):** Derived from `FDPS-A` and `FDPS-B` ([`FlightDataRule`]).
   * Each service card shows its status badge, whether redundancy is lost, and the confirmed or unconfirmed reasons behind its current state.
3. **Equipment Grid & Detail Drawer:**
   * Shows one tile per supervised device (`RAD-N`, `RAD-E`, `RAD-S`, `SDPS-A`, `SDPS-B`, `FDPS-A`, `FDPS-B`), grouped by domain (`SURVEILLANCE`, `FLIGHT DATA`).
   * Clicking a tile opens its **Detail Panel**, showing sub-component status (radar channels A and B, or the SDPS sensor input table), telemetry values, poll freshness, and the last 50 events for that device.
4. **Alarm List:**
   * Lists open alarms (`ACTIVE_UNACK`, `ACTIVE_ACK`, `CLEARED_UNACK`), ordered by severity (`CRITICAL`, `MAJOR`, `MINOR`, `WARNING`) and then by raise time (newest first).

---

## 2. Reading Status, `UNKNOWN`, and Maintenance

### 2.1 Health Is Never Shown by Colour Alone

Every equipment and service status badge uses a **colour + shape/icon + text label**:

| Status | Colour | Symbol | Meaning |
|---|---|---|---|
| **`OPERATIONAL`** (`OK` on services) | Green | `✓` | Confirmed working normally with full redundancy. |
| **`DEGRADED`** | Amber | `▲` | Working with reduced capability (e.g. one radar channel down, or fewer than 2 radars) or lost standby redundancy (down to one SDPS/FDPS unit). |
| **`FAILED`** | Red | `✕` | Confirmed unable to perform its function. |
| **`UNKNOWN`** | Violet / Magenta (hatched) | `?` | The supervisor cannot confirm the current state. **Never shown as green.** |

### 2.2 Understanding `UNKNOWN` Reasons

When an equipment tile shows `? UNKNOWN`, itslast known telemetry values are greyed out and the tile shows why the state is unknown:

* **`NOT_YET_POLLED`:** The supervisor has just started for the first time and has not polled this device yet.
* **`LOST_CONTACT`:** Three consecutive lightweight `sysUpTime.0` probes failed (~15 s). Supervisor alarm `901` (*Lost contact*) is raised.
* **`AWAITING_STATUS`:** A probe just restored contact after `LOST_CONTACT`, and the immediate verification full poll is in flight.
* **`STATUS_UNAVAILABLE`:** Lightweight probes are succeeding, but full status polls have failed for more than 3 poll intervals (~15 s).
* **`SUPERVISOR_RESTARTED`:** The supervisor process restarted and has preserved the device's last-known role and maintenance flag while waiting for its first poll.

When a service depends on an `UNKNOWN` device that could change the service outcome, the service card shows its optimistic upper bound, for example:
* `FD ? UNKNOWN · at best OK` (*"Depends on equipment in UNKNOWN state: FDPS-B"*)

When a service's status is already determined by confirmed facts (for example, two radars have failed so `SURV` is `DEGRADED`), any additional impact from an `UNKNOWN` device is listed separately as `"Unconfirmed: …"` without raising a false redundancy alarm ([CR-001](safety/CR-001-auto-switchover-visibility.md)).

### 2.3 Maintenance Mode (`MAINT`)

Maintenance (`atmTsEqMaintenanceMode`) is displayed **alongside** hardware health, never instead of it:
* A tile in maintenance carries a **`MAINT`** badge on top of its real health (`OPERATIONAL`, `DEGRADED`, or `FAILED`).
* Service rules treat a device in maintenance as **unavailable** (a planned outage).
* Alarms raised by a device while it is in maintenance are recorded with `suppressed = true` and hidden from the default alarm view; use the alarm filter to inspect suppressed alarms.

### 2.4 Freshness Age

Each equipment tile and detail panel shows how long ago the last full poll succeeded (e.g. `2s ago`). If more than **2× the poll interval** (> 10 s) passes without a fresh poll, the age turns **amber** to warn that the displayed values are ageing.

---

## 3. Handling Alarms

| Alarm State | Badge | What It Means & How to Act |
|---|---|---|
| **`ACTIVE_UNACK`** | `UNACK` | The fault is active now and has not been acknowledged. Select the alarm (or click **Ack all visible**) to acknowledge it. |
| **`ACTIVE_ACK`** | `ACK` | You have acknowledged the alarm, and the fault is still present on the device. It stays in the list until the fault clears on the device, at which point it closes automatically. |
| **`CLEARED_UNACK`** | `CLEARED-UNACK` | The fault cleared on the device *before* you acknowledged it. It stays in the list so fleeting faults are not missed; acknowledge it to close and remove it from the open list. |

### Special Alarm Behaviours

* **Unconfirmed Marker (`?`):** While a device is `UNKNOWN`, its existing open alarms are not cleared because the supervisor cannot verify whether the fault cleared. They stay in the list marked as unconfirmed (`?`) until contact returns and the next full poll walks the device's alarm table (`via RESYNC`).
* **Severity Escalation:** If an acknowledged (`ACK`) alarm worsens in severity on the device, it automatically becomes `UNACK` again so you see the escalation.

---

## 4. Planned Operator Capabilities (`v0.2` – `v0.3`)

* **Additional Equipment & Services (`v0.2`):** ADS-B gateway (`ADSB-GW`), hot/hot voice switches (`VCS-A/B`), remote VHF radio sites (`RADIO-1/2`), legal recorder (`REC`), and SNMPv2c `IF-MIB` LAN switches (`LAN-A/B`), plus the `VOICE`, `REC`, and `NET` services and scripted scenarios.
* **Supervisor Control Commands (`v0.3`):** Issuing `SWITCHOVER`, `MAINTENANCE_ON`, `MAINTENANCE_OFF`, `RESTART`, and `RESET_COUNTERS` directly from the detail panel with supervisor-side interlocks, service-impact preview, zero-retry SNMP `SET`, and `UNCERTAIN` outcome tracking. (In `v0.1.1`, faults are injected via `/sim/api` and raw `snmpset` commands can be tested from the CLI; see the [MIB guide](mib.md#trying-it).)
* **Historical Replay & Export (`v0.3`):** Scrubbing through past incidents on `/replay` with a non-dismissible `REPLAY · NOT LIVE` banner, `NO_DATA` supervisor outage detection, and `CSV`/`JSON` incident export.

[`SurveillanceRule`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/SurveillanceRule.java
[`FlightDataRule`]: ../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/FlightDataRule.java
