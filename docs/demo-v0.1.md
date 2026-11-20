# Demo walkthrough, v0.1 (6 to 8 minutes)

A scripted tour of Demo ACC. Every step has the command, what the board shows and a talking point.
Faults are injected from the Simulator screen, which the supervisor forwards to the simulator. The timings below were measured on a
running stack; they are typical, not guaranteed. Section links point to the
[design](design/2026-11-13-atm-supervisor-design.md) (§) and to the
[architecture review amendment](refactor/ATM-Supervisor-Architecture-Review-Decisions.md) (amendment §).

Sign in as `supervisor1` and open two windows: the board, and the **Simulator** screen from the header. Each
fault step below names the button to click in a device's row on the Simulator screen.

## 0. Preflight

```sh
scripts/demo-preflight.sh --build   # once, while online
scripts/demo-preflight.sh           # before every demo
```

The script starts the stack, resets the simulator and waits for the board. It prints
`READY  7/7 OPERATIONAL  SURV OK  FD OK  http://localhost:8080` and exits 0. Add `--fresh` for an empty
alarm history. Open <http://localhost:8080>.

## 1. Nominal board

Command: none. Open the board.

Shows: 7 equipment tiles OPERATIONAL, SURV and FD both OK, the alarm list empty ("No alarms") on a fresh
start. SDPS-A and FDPS-A are ACTIVE, SDPS-B and FDPS-B are STANDBY.

Talking point: every device is polled over SNMPv3 authPriv (SHA-256 and AES-128) every 5 s, and a
lightweight probe runs on its own schedule that the circuit breaker never gates, so a tripped breaker
cannot hide a recovered device (amendment §1, design §6.1).

## 2. RAD-N fails

On the Simulator screen: **Fail** on `RAD-N`.

Shows, at once (the traps arrive within a second): RAD-N FAILED with alarms 101 "Equipment failed"
(CRITICAL), 201 "Channel A failed" and 202 "Channel B failed" (both MAJOR). Both SDPS units raise alarm
401 "Sensor input lost" (MINOR) for RAD-N. SURV stays OK: with two radars left it is still fully served.

The alarm list shows one row for the incident: `RAD-N Equipment failed` with a button `+4 correlated · 4 unack`.
Click it: 201 and 202 read "on the failed unit", and the two 401 alarms read "input from RAD-N".

Talking point: the service rule is about what is still available, not about any single failure
(§5.2). Alarm 401 shows that the SDPS notices the lost sensor input by itself. The grouping comes from three
fixed rules over the inventory, recorded with each alarm when it is raised, so replay will show the same list.

## 3. RAD-E fails, then acknowledge

On the Simulator screen: **Fail** on `RAD-E`.

Shows: RAD-E FAILED with 101, 201 and 202, and two more 401 alarms on the SDPS units. SURV turns DEGRADED
with the reason "Fewer than 2 radars available". Redundancy is not flagged lost.

Ten new alarms have now arrived within seconds, so the flood starts: an amber `ALARM FLOOD` chip appears in the
header, and the alarm bar reads `ALARM FLOOD since …Z · correlated alarms are grouped under their cause`. The
list has two cause rows, `RAD-N` and `RAD-E`, each with `+4 correlated · 4 unack`.

Then press "Ack all visible" in the alarm list. The alarms change from UNACK to ACK and stay in the list
because they are still active; the unacknowledged count drops to 0.

Talking point: an alarm closes only when it is both cleared and acknowledged (§8.2). Acknowledging does not
hide a live failure. Ten new alarms in 10 minutes is the usual alarm-management benchmark for a flood (ISA-18.2,
EEMUA 191); the supervisor ends it below 5, so it does not flicker. Grouping never hides an alarm: a correlated
alarm sits under its cause only while the cause is active, and is never more severe than it.

## 4. SDPS-A fails

On the Simulator screen: **Fail** on `SDPS-A`.

Shows, at once: SDPS-A FAILED with alarm 101, SURV reasons "Only one SDPS unit available" and "Fewer than 2
radars available", and the supervisor alarm 903 "Service redundancy lost: Surveillance" (MAJOR). About 4 s
later the switchover is visible: SDPS-B becomes ACTIVE, SDPS-A shows STANDBY (still FAILED), and SDPS-B
raises alarm 104 "Switchover occurred" (WARNING).

Talking point: the simulated pair switches over by itself and the supervisor reports the change of role
and the lost redundancy, because failover does not mean the service is still redundant (§5.2, §8.2).

## 5. FDPS-B becomes unreachable

On the Simulator screen: **Unreachable** on `FDPS-B`.

Shows: nothing changes for about 15 s while polls and probes fail. At about 16 s FDPS-B turns UNKNOWN
(reason LOST_CONTACT), alarm 901 "Lost contact" is raised with a "?" next to it, and FD turns UNKNOWN,
shown as "UNKNOWN · at best OK", with the reason "Depends on equipment in UNKNOWN state: FDPS-B".

Talking point: the board never shows green for something it cannot see. The "?" means the alarm belongs to
a device whose state is unconfirmed, and "at best OK" tells the operator the best case if the unit is in
fact fine (§5.1).

## 6. FDPS-B recovers

On the Simulator screen: **Recover** on `FDPS-B`.

Shows: within a few seconds (at most one probe interval) FDPS-B passes through AWAITING_STATUS (still
UNKNOWN) for a fraction of a second and then returns to OPERATIONAL. FD returns to OK and alarm 901
becomes CLEARED-UNACK. Acknowledge it and it leaves the list.

Talking point: a recovered probe triggers one immediate verification poll instead of waiting for the
breaker timer, so recovery takes seconds, not about 30 s (amendment §1).

## 7. Ask Impact what one more failure would cost

Command: none. Open **Impact** in the header, with RAD-N, RAD-E and SDPS-A still failed from steps 2 to 4.

Shows: the table of single points of failure lists SDPS-B and RAD-S as `✕ SERVICE LOSS`, because losing either
leaves Surveillance with no SDPS unit or no radar, and FDPS-A and FDPS-B as `▲ REDUCED MARGIN`. The three failed
units are listed last as out of service. Tick `FDPS-B` under **Plan maintenance**: the plan reads `▲ REDUCED MARGIN`
(`FD` goes from OK to DEGRADED, `Only one FDPS unit available`), `FDPS-A` becomes `✕ SERVICE LOSS`, and the
address of the page now carries the plan. Arm **Maintenance on** for `FDPS-B` on the board, and the confirm bar
shows the same impact; cancel it.

Talking point: the screen reruns the board's own service rules with chosen units out, so it cannot disagree with
the board. It says what the rules count and nothing else, shows its basis and the time of its answer, and never
blocks a command: the supervisor decides.

## 8. RAD-S loses its traps, then fails

On the Simulator screen: **Drop traps** on `RAD-S`, then **Fail** on `RAD-S`.

Shows: RAD-S FAILED with 101, 201 and 202 appears within one poll (0.5 to 4 s in the runs; the poll
interval is 5 s), SURV FAILED with 904 "Service failed: Surveillance". No trap was sent. The alarms
were raised by the poll's alarm table walk, which the event log records as `via RESYNC`, where the
RAD-N and RAD-E alarms were `via TRAP`.

Talking point: polling is the source of truth and traps are only hints that make the board faster
(§6.1, §8.2). A lost trap delays an alarm by at most one poll; it cannot hide the failure.

## 9. Ask the agent directly

The macOS `snmpget` is too old, so use the README's container command (the network name is
`<compose project>_default`, `atm-supervisor_default` for the demo stack):

```sh
docker run --rm --network atm-supervisor_default alpine:3.22 sh -c 'apk add -q net-snmp-tools && snmpget -v3 -l authPriv -u monitor -a SHA-256 -A monitor-auth-demo -x AES -X monitor-priv-demo simulator:16101 1.3.6.1.2.1.1.3.0'
```

Shows: `DISMAN-EVENT-MIB::sysUpTimeInstance = Timeticks: (…) …` from RAD-N. This needs the network
once, to install the package.

Talking point: the agents are real SNMPv3 agents, not a mock; the supervisor uses the same protocol (§6.1).

## 10. Reset

On the Simulator screen, click **Reset all**, then **Confirm**.

Shows: within a poll or two every device is OPERATIONAL again, roles go back to SDPS-A and FDPS-A
ACTIVE, SURV and FD are OK, and the remaining alarms show CLEARED-UNACK until acknowledged. The `ALARM FLOOD` chip goes once fewer than 5
alarms have been raised in the last 10 minutes, which can take a while. For a clean
board next time, run `scripts/demo-preflight.sh --fresh`.

## If asked

- **Why does the breaker never gate probes?** A breaker that blocks the only way to see a device would hide
  its recovery. Probes run apart from it and a rejected call is not a contact attempt (amendment §1).
- **What are the two generations?** The status generation (bumped by traps and contact changes) guards full
  polls; the contact generation (bumped by contact changes only) guards probes. A trap therefore cannot
  discard a probe result and mask a lost contact (amendment §2, design §8.1).
- **What happens to a slow SSE client?** Each client has a bounded queue. When it overflows the client is
  dropped and reconnects; the writer never blocks on delivery (amendment §4, design §8.1).
- **Why does the UI refetch `/board` on reconnect?** A reconnect may have missed events beyond the ring
  buffer, so the UI loads the whole board before it resumes the stream instead of trusting a partial
  history (amendment §4, design §8.5).
- **If every poll reconciles alarms, why still use traps?** Traps raise alarms at once, a poll is up to 5 s
  away. Resync catches whatever a trap lost (§6.1, §8.2).
