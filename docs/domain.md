# Domain primer

This page explains the air traffic management (ATM) ideas that ATM Supervisor models. It is written
for backend engineers who are new to the domain. Everything here describes a fictional centre,
**Demo ACC**, and a deliberately simplified model of it. Nothing in this repository is certified or
suitable for operational use.

## Technical supervision is not air traffic control

An area control centre (ACC) gives air traffic control to aircraft en route. Controllers work with
radar pictures, flight data and voice. Behind them runs a large technical estate: radar stations,
the surveillance tracker, the flight data processor, voice switches, radios, recorders and networks.

**Technical supervision** watches that estate. A **System Controller** at a technical supervision
position sees the health of every system, handles alarms, coordinates maintenance, and tells the
operational supervisor what an equipment problem means for the service controllers receive. The
System Controller does not separate aircraft. ATM Supervisor is a tool for that technical position:
it never touches flight or surveillance data itself, only the management interfaces of the systems
(SNMP), and it derives service impact from their state.

## The supervised systems in this release

Demo ACC currently has three device types and seven devices. The other device types in the
[design](design/2026-11-13-atm-supervisor-design.md#5-the-simulated-centre-demo-acc) are planned.

| Device | Type | What it does |
|---|---|---|
| `RAD-N`, `RAD-E`, `RAD-S` | Radar | Mode S radar stations. Each has two transmitter channels, A and B. |
| `SDPS-A`, `SDPS-B` | SDPS | Surveillance data processing: the multi-sensor tracker that turns radar plots into the system tracks controllers see. An active/standby pair. |
| `FDPS-A`, `FDPS-B` | FDPS | Flight data processing: flight plans and the messages that update them. An active/standby pair. |

```mermaid
flowchart LR
  RADN[RAD-N] --> SDPS
  RADE[RAD-E] --> SDPS
  RADS[RAD-S] --> SDPS
  subgraph SDPS[SDPS pair]
    SA[SDPS-A] --- SB[SDPS-B]
  end
  subgraph FDPS[FDPS pair]
    FA[FDPS-A] --- FB[FDPS-B]
  end
  SDPS --> SURV([SURV service])
  FDPS --> FD([FD service])
```

**Radar to tracker.** Each SDPS unit reports one sensor input row per radar, as `receiving`, `lost`
or `excluded`. When a radar fails, both SDPS units report its input as lost and raise alarm 401
*Sensor input lost* for it. The tracker keeps working on the remaining radars, so one failed radar
is a reduction in coverage depth, not a loss of surveillance.

**Active/standby pairs.** Only the active unit of an SDPS or FDPS pair does the work. If the active
unit fails and the standby is usable (not failed, not in maintenance), the pair switches over about
3 seconds later. The new active unit raises alarm 104 *Switchover occurred*. A pair that has
switched over still works, but it has lost its redundancy.

## Equipment health and service state are different things

Each device has four independent attributes:

- **Health:** `OPERATIONAL`, `DEGRADED` or `FAILED`, as the device reports it, or `UNKNOWN` (below).
- **Maintenance:** a flag, not a health value. A device in maintenance still shows its real health.
- **Role:** `standalone`, `active` or `standby`.
- **Contact:** whether the supervisor can reach it, and when it last could.

A **service** is what the operational side actually depends on. ATM Supervisor derives two services
from equipment state, with one small rule class each:

| Service | `FAILED` when | `DEGRADED` when |
|---|---|---|
| `SURV` surveillance ([`SurveillanceRule`](../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/SurveillanceRule.java)) | No SDPS unit is available, or no radar is available | Only one SDPS unit is available, or fewer than 2 radars are available |
| `FD` flight data ([`FlightDataRule`](../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/FlightDataRule.java)) | No FDPS unit is available | Only one FDPS unit is available |

Otherwise the service is `OK`. A device is **available** when its health is `OPERATIONAL` or
`DEGRADED` and it is not in maintenance, so maintenance counts as a planned outage. A service that
is down to one unit also raises supervisor alarm 903 *Service redundancy lost*, and a failed service
raises 904 *Service failed*.

## N-1 and single points of failure

Engineers who plan work on a centre ask "what if one more thing fails?". That is **N-1**: with everything as it is
now, or with some units already out for maintenance, would the loss of any one further unit cost a service? A unit
whose loss would is a **single point of failure** for that service, even though nothing is wrong yet. With `FDPS-A`
and `FDPS-B` both operational, neither is a single point of failure for `FD`. With `FDPS-B` in maintenance, `FDPS-A`
is one.

Because the service rules above are functions of which units are available, N-1 needs no new model. The impact
analysis reruns the same rules with the extra units counted as unavailable and compares the result with the
reference: the board as it is for a maintenance plan, or the board with the plan applied for N-1. It gives one of
three verdicts:

- **`SERVICE LOSS`:** a service that is not failed would become `FAILED`.
- **`REDUCED MARGIN`:** no service would fail, but one gets worse (`OK` to `DEGRADED`), newly loses redundancy or gains
  a reason.
- **`NO SERVICE IMPACT`:** nothing changes. This is not a clearance: it says only what the rules count.

A unit in `UNKNOWN` state is handled as in the next section: the analysis takes the worse of its two passes and marks
the result unconfirmed. The rules know nothing about roles, so planning an active unit adds a note that the pair
switches over, and the verdict stays as the rules give it. The analysis is a view of the rules, and it does not model
a switchover's brief interruption or a radar's coverage; it is not a safety assessment.

## `UNKNOWN` belongs to the supervisor

Devices only ever report `OPERATIONAL`, `DEGRADED` or `FAILED`. `UNKNOWN` is the supervisor saying
"I cannot confirm this device's health right now". It always overrides the last known value and
always carries a reason:

| Reason | Meaning |
|---|---|
| `NOT_YET_POLLED` | No successful poll yet. |
| `LOST_CONTACT` | Three consecutive probes failed, about 15 s at the default 5 s interval. |
| `AWAITING_STATUS` | Contact is back, but no full poll has confirmed the health yet. |
| `STATUS_UNAVAILABLE` | Probes succeed, but no full poll has succeeded for more than 3 poll intervals. |
| `SUPERVISOR_RESTARTED` | The supervisor restarted and has not polled the device yet. |

`UNKNOWN` is never shown as green or `OK`. For a service, the supervisor evaluates the rule twice:
once with every `UNKNOWN` device treated as available, and once with every `UNKNOWN` device treated
as unavailable.

- **Both passes agree:** that is the service state, because the unknown devices cannot change it.
  A known failure is therefore never hidden behind "unknown". Only confirmed reasons are shown, plus
  one "Unconfirmed: …" reason naming the unknown equipment.
- **The passes disagree:** the service is `UNKNOWN` and shows its best case, for example
  "UNKNOWN · at best OK".

Service alarms follow confirmed facts only. If the passes disagree on whether redundancy is lost,
alarm 903 is neither raised nor cleared until the facts are confirmed.

## Lost contact is not equipment failure

When the supervisor cannot reach a device's management agent, it knows nothing new about the
device, and that is all it knows. The radar may still be turning and feeding the tracker. In the
simulator, a radar whose SNMP agent stops answering keeps feeding both SDPS units, exactly as a real
radar would behind a broken management link.

So lost contact makes the device `UNKNOWN (LOST_CONTACT)` and raises alarm 901 *Lost contact*. It
does not make the device `FAILED`, and it does not clear its open alarms: they stay open with an
"unconfirmed" marker, because the supervisor cannot confirm that they cleared. When contact
returns, the device stays `UNKNOWN (AWAITING_STATUS)` until a full poll confirms its health, and
that poll reconciles the alarms against the device's own alarm table.

## Alarms

An alarm is identified by equipment, code and instance (for example `SDPS-A`, 401, sensor `RAD-N`).
There is at most one open alarm per identity. Its lifecycle is the usual one in alarm handling:
an alarm closes only when it is both cleared and acknowledged.

```mermaid
stateDiagram-v2
  [*] --> ACTIVE_UNACK: raise
  ACTIVE_UNACK --> ACTIVE_ACK: acknowledge
  ACTIVE_UNACK --> CLEARED_UNACK: clear
  CLEARED_UNACK --> CLOSED: acknowledge
  ACTIVE_ACK --> CLOSED: clear
  ACTIVE_ACK --> ACTIVE_UNACK: escalate
  CLEARED_UNACK --> ACTIVE_UNACK: raise again (same alarm)
```

- **Escalation** to a higher severity makes an acknowledged alarm unacknowledged again, because the
  operator must see it. **De-escalation** keeps the acknowledgement.
- **Maintenance:** alarms from a device in maintenance are still recorded, but flagged as suppressed
  and hidden by default.
- **Device alarms** come from traps and from the device's alarm table. **Supervisor alarms** are
  derived by the supervisor: 901 lost contact, 903 redundancy lost and 904 service failed.
- **Traps are hints.** A trap raises or clears an alarm at once, but every full poll walks the
  device's alarm table and corrects the supervisor's view, so a lost trap cannot hide an alarm
  beyond the next successful full poll.

## What is simplified

- **No real data.** Radar plots, tracks and flight plans appear only as counters and rates. Nothing
  processes real surveillance or flight data.
- **A small estate.** Three device types and two services today. Voice, radio, recording, ADS-B and
  network supervision are planned.
- **Availability only.** The service rules count available units. A `DEGRADED` active unit does not
  yet degrade its service.
- **Three roles.** A VIEWER watches the board and the alarm history. A SUPERVISOR also acknowledges
  alarms, sends equipment commands (maintenance, restart, make active) and, in the demo centre,
  injects training faults. An ADMIN can do all of that and manages the accounts; replay is planned.
- **One supervisor.** The supervisor itself is not redundant.
- **Illustrative values.** Alarm codes, severities, thresholds and timings are plausible defaults,
  not taken from any real system.

## Glossary

| Term | Meaning |
|---|---|
| ACC | Area control centre: the unit that controls traffic en route. |
| ADS-B | Automatic Dependent Surveillance–Broadcast: aircraft broadcast their own position. |
| AFTN, OLDI | The networks over which flight data messages arrive: the aeronautical fixed telecommunication network, and on-line data interchange between neighbouring centres. |
| CNS | Communication, navigation and surveillance: the technical infrastructure behind ATM. |
| Degraded mode | Operation with reduced capability or without redundancy. |
| FDPS | Flight data processing system. |
| Mode S | A secondary surveillance radar mode that interrogates aircraft selectively. |
| SDPS | Surveillance data processing system: the multi-sensor tracker. |
| System Controller | The person at the technical supervision position. |
