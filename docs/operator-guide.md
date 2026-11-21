# User and Operator Guide

This guide explains how to start, read, operate and troubleshoot ATM Supervisor. It covers each screen of the console,
eight step-by-step workflows, the REST APIs, and common checks when something does not behave as expected.

For background on technical supervision in an Area Control Centre, see the [domain primer](domain.md). For a shorter
scripted tour, see the [demo walkthrough](demo-v0.1.md).

## 1. Starting, resetting and stopping the stack

### 1.1 First-time build (online)

From the repository root, build the container images, start the stack, reset the simulator, and wait until all 7
devices report `OPERATIONAL`:

```sh
scripts/demo-preflight.sh --build
```

When the stack is ready, the script prints:

```text
READY  7/7 OPERATIONAL  SURV OK  FD OK  http://localhost:8080
```

### 1.2 Everyday start and clean reset (offline)

Once the images are built, the stack needs no internet connection:

```sh
# Start (or reuse) the running stack and reset all simulator faults
scripts/demo-preflight.sh

# Wipe all stored alarms, event history, and engine boot counters first
scripts/demo-preflight.sh --fresh
```

You can also run the stack directly with Docker Compose from the repository root:

```sh
docker compose up -d --wait
docker compose down          # stop containers, keep the database and engine-boots volumes
docker compose down -v       # stop containers and delete volumes
```

Open <http://localhost:8080> in your browser.

### 1.3 Signing in

The board opens with the sign-in screen. In demo mode it lists three accounts; select one to fill in the form:

| Account | Role | What it can do |
|---|---|---|
| `admin1` | ADMIN | Everything a supervisor can, plus the **Users** screen |
| `supervisor1` | SUPERVISOR | Acknowledge alarms, send equipment commands, and use the **Simulator** screen |
| `demo1` | VIEWER | View the board, Alarm history, Impact, and Replay |

Each password is the account name followed by `-demo`. **Create a read-only account** registers a new VIEWER. When a
session ends (after 12 hours without the board open, or when the supervisor restarts), the board returns to the
sign-in screen with the notice *Your session ended. Sign in again.* **Sign out** is at the right of the header. It
ends the session in every window, and so does an administrator's change to your account: every request from that
session is refused at once, and a window showing the board returns to the sign-in screen within seconds. A window on
another screen returns to sign-in at its next action, and the Simulator screen within its two-second refresh.

## 2. Reading the operator board

The console is a full-viewport grid with four areas: the header bar, the services strip, the equipment board with its
detail panel, and the alarm pane.

![Nominal board: all 7 devices operational and both services OK](images/board-nominal.png)

### 2.1 Top header bar (`ATM SUPERVISOR · DEMO ACC`)

- **Connection status:**
  - `● LIVE` (green): connected to the Server-Sent Events stream (`/api/v1/stream`).
  - `◌ RECONNECTING` (amber): stream interrupted; the browser is reconnecting and will replay missed events with
    `Last-Event-ID` or reload `/api/v1/board`.
  - `✕ OFFLINE` (red): disconnected.
- **`ALARM FLOOD` chip (amber):** shown next to the connection indicator on the board while an alarm flood lasts; its
  accessible name carries the start time. Only the board page shows it, because only the board runs the live stream.
  See [2.6.1](#261-correlated-alarms-and-the-alarm-flood).
- **UTC clock:** shows server-synchronised UTC time with a `Z` suffix (for example `15:24:18Z`).
- **`DATA NOT LIVE` overlay:** if the stream stays down for more than 10 seconds, a hatched overlay covers the board
  so an operator cannot mistake a frozen browser tab for live supervision.

### 2.2 Operational services strip (`SURV` and `FD`)

Below the header sits the services strip:

- **`SURV` (Surveillance):** whether controllers have a working multi-sensor tracker (`SDPS-A/B`) fed by at least two
  radars (`RAD-N/E/S`).
- **`FD` (Flight Data):** whether controllers have a working flight data processor (`FDPS-A/B`) with standby
  redundancy.
- **Reasons (`title` tooltip):** pointing at a service chip shows the reasons behind its status, for example
  `Fewer than 2 radars available; Only one SDPS unit available` or
  `Unconfirmed: Only one SDPS unit available (equipment in UNKNOWN state: SDPS-B)`.

### 2.3 Status badges (colour, symbol and text)

Status is never shown by colour alone:

| Badge | Symbol | Colour | Meaning |
|---|---|---|---|
| `✓ OPERATIONAL` / `✓ OK` | `✓` | Green | Confirmed healthy. |
| `▲ DEGRADED` | `▲` | Amber | Working with reduced capability (one radar channel failed, or fewer than 2 radars available) or without standby redundancy (down to one SDPS or FDPS unit). |
| `✕ FAILED` | `✕` | Red | Confirmed unable to perform its function. |
| `? UNKNOWN` | `?` | Violet (hatched) | The supervisor cannot confirm the current state. On a service chip, it also shows the best case: `? UNKNOWN · at best OK` or `? UNKNOWN · at best DEGRADED`. |

### 2.4 Equipment tiles (`SURVEILLANCE` and `FLIGHT DATA`)

![Board during a failure: RAD-N failed, SDPS-A unknown, FDPS switched over to B](images/board-failure-and-unknown.png)

Each equipment tile (`RAD-N`, `RAD-E`, `RAD-S`, `SDPS-A`, `SDPS-B`, `FDPS-A`, `FDPS-B`) shows:

1. **Identifier and pair role:** the top row shows the device ID (`SDPS-A`) and, for redundant pairs, its role
   (`ACTIVE` or `STANDBY`).
2. **Health badge and `MAINT` tag:**
   - The health badge (`✓ OPERATIONAL`, `▲ DEGRADED`, `✕ FAILED` or `? UNKNOWN`).
   - When `atmTsEqMaintenanceMode` is on, a blue `MAINT` badge appears next to the unit's real hardware health.
3. **`UNKNOWN` reason and last contact:**
   - When a device is `? UNKNOWN`, the tile is hatched and says why:
     - `last contact <HH:MM:SSZ>` when contact is lost (after 3 failed probes, about 15 s).
     - `awaiting status` when contact has just returned on a probe and the immediate verification full poll has not
       finished yet.
     - `status unavailable` when probes succeed, but full polls have failed for more than 3 poll intervals.
     - `not yet polled` before the first poll on a fresh start.
     - `supervisor restarted` after a supervisor restart before the first poll completes. The tile reads
       `UNKNOWN (SUPERVISOR_RESTARTED) since <restart>` and keeps `last contact <HH:MM:SSZ>`, the last time the
       supervisor had heard from the unit before it restarted.
   - While a unit is `UNKNOWN`, its numerical values (`Plots/scan`, `Rotation ms`, `Tracks`, `Latency ms`, `Plans`,
     `Queue`) are dimmed to 40% opacity so they are never read as current measurements.
4. **Stale-poll age (`N s ago`):**
   - Under normal polling (every 5 s), tiles show no poll age. If more than 2 poll intervals (> 10 s) pass since
     `lastPollAt` before the device reaches `LOST_CONTACT`, an amber `11 s ago` warning appears on the tile.

### 2.5 Equipment detail panel

Click any equipment tile to open its detail panel on the right (deep-linked at `/board/:equipmentId`, for example
<http://localhost:8080/board/SDPS-A>):

- **Header and duration:** the full name (`Surveillance Data Processing A SDPS-A`), health badge,
  `since <HH:MM:SSZ>`, and any `UNKNOWN` reason.
- **Role and maintenance:** `Role` (`ACTIVE`, `STANDBY`, `STANDALONE`) and `Maintenance` (`MAINT` or `no`).
- **Values and tables:**
  - When the unit is `UNKNOWN`, a `Last known values` heading appears and the values are dimmed.
  - **Radar (`RAD-N/E/S`):** `Rotation (ms)`, `Plots/scan`, `Active channel` (`CHANNEL_A` / `CHANNEL_B`),
    `Channel A` status, and `Channel B` status.
  - **SDPS (`SDPS-A/B`):** `Tracks`, `Latency (ms)`, and the `Sensor / Input` table listing each radar (`RAD-N`,
    `RAD-E`, `RAD-S`) as `RECEIVING`, `LOST`, or `EXCLUDED`.
  - **FDPS (`FDPS-A/B`):** `Flight plans`, `Queue depth`, and `Latency (ms)`.
- **Poll freshness (`Last poll N s ago`):** ticks every second against server time.
- **Recent events:** the last 50 durable events stored for this unit (`EQUIPMENT_STATE_CHANGED`, `CONTACT_LOST`,
  `CONTACT_RESTORED`, `ALARM_RAISED`, `ALARM_CLEARED`, `EQUIPMENT_COMMANDED`, and so on) with their UTC times and
  summaries.
- **Control (supervisors and administrators):** **Maintenance on** or **Maintenance off**, **Restart** (only in
  maintenance; it is also greyed out if the board shows a restart in progress, which is rare because the unit stops
  answering as soon as it restarts), and **Make active** (for the standby unit of a pair, enabled only while it is
  operational and not in maintenance). A greyed-out button says why when you point at it. See
  [Playbook 4](#playbook-4-maintenance-mode-alarm-suppression-and-equipment-commands).
- **Impact preview:** when you arm **Maintenance on**, the confirm bar also reads, for example,
  `Impact at 10:15:02 UTC: REDUCED MARGIN - FD: Only one FDPS unit available`. It adds a note when the unit is the
  active one of its pair, and ends with ` (unconfirmed)` when the verdict depends on units in `UNKNOWN` state. While
  the bar is armed, the preview asks again every 5 s, so it follows the board, and its time shows how old the answer
  is. It shows `Impact: assessing…` until the first answer and `Impact could not be assessed` if that fails. A later
  refresh that fails keeps the last answer and adds ` (last refresh failed)`. **Confirm** stays enabled in every
  case: the preview informs, and you decide. The same answer, for any set of units, is on the **Impact** screen
  ([2.10](#210-impact-plan-maintenance-and-find-single-points-of-failure)).

### 2.6 Alarms pane

The bottom pane lists every open alarm (`ACTIVE_UNACK`, `ACTIVE_ACK`, `CLEARED_UNACK`):

- **Order:** sorted by severity (`CRIT` -> `MAJ` -> `MIN` -> `WARN`) and then by raise time (newest first).
- **Columns:**
  - Checkbox (for selecting individual alarms)
  - `Sev` (`CRIT`, `MAJ`, `MIN`, `WARN`)
  - `Time` (UTC time when the alarm was raised)
  - `Equipment` (`RAD-N`, `SDPS-A`, or service ID `SURV` / `FD` for supervisor alarms `903` and `904`)
  - `Alarm` (description, instance name such as `· RAD-N`, and a violet `?` badge if the equipment is `UNKNOWN` and
    its alarms are therefore unconfirmed)
  - `State` (`UNACK`, `ACK`, or `CLEARED-UNACK`)
- **Grouping:** alarms that a known failure explains are listed under the alarm of that failure. See
  [2.6.1](#261-correlated-alarms-and-the-alarm-flood).
- **Controls:**
  - **`Show suppressed (N)` checkbox:** shows alarms from units in `MAINT` mode (hidden by default so planned work
    does not clutter the watch).
  - **`Ack selected` button:** acknowledges the ticked rows.
  - **`Ack all visible` button:** acknowledges every `UNACK` and `CLEARED-UNACK` alarm visible in the table,
    including alarms inside collapsed groups.

#### 2.6.1 Correlated alarms and the alarm flood

One failure raises several alarms. The supervisor links each alarm that a known failure explains to that failure's
`101 Equipment failed` alarm, its **cause**, and lists them together. The
[domain primer](domain.md#correlated-alarms-and-floods) explains the three rules.

- **A group row:** the cause is one row, for example `RAD-N Equipment failed`, with a button `+4 correlated`. When
  any of the correlated alarms is unacknowledged, the button also says how many: `+4 correlated · 4 unack`. Groups
  are collapsed by default and sorted by their worst severity.
- **Expand it:** click the button; click it again to collapse. The correlated alarms appear under the cause, each
  with `↳` and a note that says why it is there:
  - `input from RAD-N` for the `401 Sensor input lost` of an SDPS unit;
  - `switchover after FDPS-A failed` for the `104 Switchover occurred` of its partner;
  - `on the failed unit` for any other alarm of the failed unit, such as `201` and `202`.
- **Acknowledge a group:** the checkbox of a collapsed group selects the cause and every correlated alarm
  (`Select alarm RAD-N Equipment failed and its 4 correlated alarms`), so **Ack selected** acknowledges them all.
  **Ack all visible** does the same for every group. Expand a group to acknowledge its alarms one at a time. The
  `unacknowledged` count in the alarm bar counts every alarm, grouped or not.
- **Nothing is hidden:** a correlated alarm is never more severe than its cause, and it is listed under the cause
  only while the cause is active and shown. If the cause clears, or is hidden by the suppressed filter, the alarm
  becomes an ordinary row again. Service alarms (`903`, `904`) and lost contact (`901`) are never grouped.
- **The flood banner:** while a flood lasts, the alarm bar shows
  `ALARM FLOOD since 14:02:10Z · correlated alarms are grouped under their cause`, and the amber `ALARM FLOOD`
  chip shows in the header. A flood starts when 10 alarms have been raised in 10 minutes and ends when fewer than 5
  have, so it does not flicker around one threshold. The banner is words as well as colour. Read the cause rows
  first, and expect more alarms.
- **History:** on **Alarm history**, the `Correlated` column shows the stored link, for example
  `↳ RAD-N 101 · input from RAD-N`. It is the last link recorded on the alarm: an alarm adopted later by a new cause
  shows that link, and a detached alarm shows none. Every link and unlink is a stored event, so replay can rebuild
  the grouping at any moment.

![The board during an alarm flood with RAD-N and RAD-E failed: the header shows the ALARM FLOOD chip, the alarm bar says ALARM FLOOD since 10:22:38Z, each radar's Equipment failed alarm is one row with +4 correlated · 4 unack, and the RAD-N group is expanded to show its two channel alarms and the two SDPS sensor input alarms](images/alarm-flood.png)

### 2.7 Alarm history

**Alarm history** in the header searches every alarm, open or closed, newest first. Filter by state (`All` or
`Open`), severity, equipment (or the service `SURV` or `FD`, for the supervisor's alarms `903` and `904`), and a
**From/To** range in UTC, then click **Search**. Each row shows when the alarm was raised, who acknowledged it and
when, when it cleared, and the cause it was correlated to, if any. **Previous** and **Next** page through 50 alarms
at a time of the search you last submitted, even while you edit the filters. If the server refuses a search, the page
shows its reason. Every row has a **Replay** link that opens the [Replay screen](#211-replay-see-the-board-as-it-was)
around that alarm.

### 2.8 Simulator (demo centre only)

Supervisors and administrators see **Simulator** in the header when the stack runs in demo mode. It lists each
simulated device with its status, role, whether it answers SNMP, and its active faults, and offers the faults its type
supports: **Fail**, **Degrade**, **Channel A fail** and **Channel B fail** (radars), **Sensor lost** with the radar it
concerns (SDPS), **Unreachable**, **Drop traps**, and **Recover**. **Reset all** asks for a confirmation first. The
screen refreshes every two seconds. Outside demo mode its API does not exist, and its address leads to the board.

### 2.9 Users (administrators)

**Users** lists every account with its role and whether it is enabled. An administrator can change a role and
**Save**, **Disable** an account after a confirmation or **Enable** it again, and **Set password** under the same rule
as registration. Every change ends that user's sessions: the server refuses their next request, their board returns
to the sign-in screen within seconds, and their other screens at their next action. Your own row is read-only, so an
administrator cannot lock themselves out.

### 2.10 Impact (plan maintenance and find single points of failure)

**Impact** in the header is open to every role. It answers two questions from the live board: which single unit, if
it failed now, would cost a service or its redundancy, and what is left of each service if some units go into
maintenance. It reruns the same service rules the board uses, so it can never disagree with the board.

- **Plan maintenance:** tick the units you are thinking of taking out. They are grouped by function. A unit that is
  already failed or in maintenance is shown ticked and greyed out with its reason. The ticks are kept in the page
  address, so you can send the link to a colleague and it survives a reload; **Clear** removes them.
- **The plan's verdict:** with a plan, the screen shows one of three verdicts, always as words and never by colour
  alone, and each service's status before and after, with its reasons and whether redundancy is lost.

  | Verdict | Meaning |
  |---|---|
  | `✕ SERVICE LOSS` | A service that is not failed now would be failed. |
  | `▲ REDUCED MARGIN` | No service would fail, but at least one gets worse: a lower status, lost redundancy or a new reason. |
  | `NO SERVICE IMPACT` | Every service keeps its status, its redundancy and its reasons. |

  `NO SERVICE IMPACT` is shown in neutral text, never in the OK green, because it describes the rules and is not a
  clearance. With no plan, the screen reads `No maintenance planned`.
- **Notes:** if a planned unit is the active one of its pair, a note says that maintenance switches the pair over to
  its partner, or that the partner cannot take over. The rules do not model switchovers, so the note never changes the
  verdict.
- **Single points of failure:** a table, worst first, lists every unit still in service with what would happen to
  the services if it failed as well, on top of the plan. Units that are failed, in maintenance or in the plan are
  listed last as `Out of service`, with the reason.
- **Unconfirmed:** a unit in `UNKNOWN` state is never read as healthy. A verdict is the worst over every possible
  state of the `UNKNOWN` units, each working or not, and is marked unconfirmed when those states disagree. The unit
  asked about, or planned, counts as working now. With more than 10 `UNKNOWN` units the verdict is a conservative
  bound, which can only overstate. Whenever the plan or any single point is unconfirmed, the screen adds
  `Unconfirmed verdicts depend on units in UNKNOWN state:` followed by their names. A service that depends on them
  reads, for example, `UNKNOWN (at best OK)`, as on the board.
- **Freshness:** the screen refreshes every 5 s and shows the time of the answer (`as of`). If a refresh
  fails or takes longer than 4 s, it keeps the last result and says `Last refresh failed at …`, so the `as of` time
  shows how old it is. A plan that names a unit the supervisor does not know shows the reason instead of a result.
- **Basis:** every result ends with `Derived from the supervised equipment and the service rules; not a safety
  assessment`. The analysis counts available units only: it does not model the brief interruption of a switchover or
  a radar's coverage. The verdict informs a decision and never blocks a command.

### 2.11 Replay (see the board as it was)

**Replay** in the header is open to every role. It shows the board as it was at a past instant and plays the events
forward, so a review of an incident starts from what the System Controller saw, not from today's rules. It is built so
that it cannot pass for the live board, and nothing on it controls equipment.

![The Replay screen after a supervisor restart, with RAD-N and RAD-E failed: the amber band reads REPLAY · 09:42:49Z · NOT LIVE · values ≤30 s · no freshness ages, each tile shows its sampled time, the timeline shades the time before the supervisor started, marks the radar alarms and the flood that the restart ended, and the ticker lists the restart's events](images/replay.png)

- **Open it** from **Replay** in the header, or from the **Replay** link on an Alarm history row. From the header
  the window is the last hour and the play head is at its start. From an alarm row the window is 15 minutes before to
  45 minutes after the alarm was raised, and the play head starts 1 minute before it. A window is at most 7 days. The
  address of the page holds the window, the instant and the selected unit (`from`, `to`, `at`, `unit`), so you can
  send it to a colleague and they see the same moment.
- **The band.** A striped amber band stays at the top and cannot be dismissed:
  `REPLAY · <HH:MM:SSZ> · NOT LIVE · values ≤30 s · no freshness ages`. The time is the play head. When the window
  spans more than one UTC day, the date shows before the time. The band has **Back to live**, which returns to the
  board. The page has no header with links, no acknowledge buttons and no equipment controls.
- **Play, pause and speed.** **Play** advances the clock at 1x, 4x, 16x or 60x real time, and **Pause** holds it.
  Playback stops at the end of the window. If events cannot be loaded, playback pauses and says so.
- **Step.** **Next event** applies the next event and moves the clock to it. **Previous event** steps to the previous
  event this session has already seen, or else to the start of the window.
- **Seek.** Click the timeline to jump to that instant. The timeline marks each alarm raised (its accessible name
  carries the time, unit and text), shades the periods of an alarm flood and of no recorded data, and shows the play
  head. Past 2,000 alarms it says `Too many alarms to mark; showing the newest 2,000`.
- **The ticker** lists the last 20 events, newest first, as `HH:MM:SSZ · <what happened>`.
- **Tiles show samples.** Values on a tile are the supervisor's 30-second samples, shown with `sampled HH:MM:SSZ`. They
  are never averaged or filled in, and a tile shows no freshness age, because there is no "now" to measure from.
  Health comes from the recorded events. A unit's value from before a supervisor restart is not shown after it.
- **The gap overlay.** `No recorded data: supervisor not running` covers the board when nothing was recorded around
  the play head: no event other than a sample in the last 90 seconds (1.5 times the 60-second heartbeat), the newest
  event is a supervisor stop, or the instant is before the first record. A gap is never shown as a healthy board.
- **Select a unit** to see its state and values in the detail panel. The panel has no commands and loads nothing live.
  The alarm list is read-only: it lists the alarms open at the play head with their state and correlation.
- **Across a supervisor restart** playback reloads the board at the restart, so every unit shows
  `UNKNOWN (SUPERVISOR_RESTARTED)` at that moment, exactly as live showed.
- **Export incident report.** Choose **CSV** or **JSON** to download the report for the window on the page. It starts
  with a summary, then lists every recorded event of the window except the samples. The file is named
  `incident-<from>-<to>`, for example `incident-20261120T1400Z-20261120T1500Z.csv`. The summary has:
  - alarms raised by severity, with suppressed alarms counted apart;
  - the causes and how many alarms each one explained;
  - each period in which a service was not `OK`, with its start, end, worst state and duration. A period already open
    at the start of the window shows `before <from>` and counts its duration inside the window;
  - each command with its time, unit, who sent it and its outcome. An intent whose outcome was never recorded shows as
    `outcome not recorded`;
  - alarm floods, lost contacts, gaps with no recorded data and supervisor restarts. A restart ends an open flood,
    lost contact or degradation, marked `ended by supervisor restart`;
  - the alarms still open at the end of the window, split into unacknowledged and acknowledged.

## 3. Operational playbooks

You can run these eight workflows against the live stack to see how the board responds to faults, acknowledgements,
switchovers, lost contact, and maintenance. Sign in as `supervisor1`, keep the board open in one browser window, and
open the **Simulator** screen from the header in a second window, so you can inject a fault and watch the board react.
**Reset all** on the Simulator screen clears every fault and restores the default roles.

### Playbook 1: Degrade a radar channel, fail the radar, and acknowledge alarms

1. **Inject a single-channel fault on `RAD-N`:** on the **Simulator** screen, click **Channel A fail** in the `RAD-N`
   row.
   - **What you see:** `RAD-N` turns `▲ DEGRADED`. Click the `RAD-N` tile: the detail panel shows
     `Active channel: CHANNEL_B`, `Channel A: FAILED`, `Channel B: OPERATIONAL`. Two `MIN` alarms appear:
     `102 Equipment degraded` and `201 Channel A failed`. Because `RAD-N` is still producing plots on Channel B,
     `SDPS-A/B` still show `RAD-N: RECEIVING` and `SURV` stays `✓ OK`.
2. **Fail `RAD-N` completely:** on the **Simulator** screen, click **Fail** in the `RAD-N` row.
   - **What you see:** `RAD-N` turns `✕ FAILED` with `CRIT` alarm `101 Equipment failed`, and `201`/`202` escalate to
     `MAJ`. Both `SDPS-A` and `SDPS-B` raise `MIN` alarm `401 Sensor input lost · RAD-N`. `SURV` stays `✓ OK` because
     2 radars (`RAD-E` and `RAD-S`) remain available.
3. **Acknowledge the alarms:** click **Ack all visible** in the alarm bar. Every row changes from `UNACK` to `ACK` and
   `0 unacknowledged` is shown, while the rows remain in the list because the faults are still active.
4. **Recover `RAD-N`:** on the **Simulator** screen, click **Recover** in the `RAD-N` row.
   - **What you see:** because you already acknowledged the alarms, they move directly from `ACK` to `CLOSED` and
     leave the list as soon as `RAD-N` recovers. Any alarm you did not acknowledge moves to `CLEARED-UNACK` and waits
     for acknowledgement before closing.

### Playbook 2: Observe an automatic 3-second A/B pair switchover

1. **Fail the active Flight Data Processor (`FDPS-A`):** on the **Simulator** screen, click **Fail** in the `FDPS-A`
   row.
2. **Watch the two-step transition on the board:**
   - **At once (0 s):** `FDPS-A` turns `✕ FAILED` (`101 Equipment failed`), `FD` turns `▲ DEGRADED`
     (`Only one FDPS unit available`), and supervisor alarm `903 Service redundancy lost: Flight data` (`MAJ`) is
     raised.
   - **After 3 seconds (about 3 to 4 s):** the pair switches over automatically. `FDPS-B` becomes `ACTIVE`, `FDPS-A`
     becomes `STANDBY` (still `✕ FAILED`), and `FDPS-B` raises `WARN` alarm `104 Switchover occurred`. `FD` remains
     `▲ DEGRADED` because the pair has no healthy standby left.

### Playbook 3: Lost contact (`UNREACHABLE`) and fast probe recovery

1. **Cut SNMP communication to `SDPS-B`:** on the **Simulator** screen, click **Unreachable** in the `SDPS-B` row.
2. **Watch the progression:**
   - **At about 10 s:** an amber poll age (`11 s ago`) appears on the `SDPS-B` tile.
   - **At about 15 s (3 failed probes):** `SDPS-B` turns `? UNKNOWN` (`last contact <UTC>`) with dimmed values, and
     raises `MAJ` alarm `901 Lost contact` with the unconfirmed `?` marker.
   - Look at the `SURV` chip in the services strip: it turns `? UNKNOWN · at best OK` because whether surveillance
     still has full SDPS redundancy depends on the unreachable `SDPS-B`.
3. **Restore communication:** on the **Simulator** screen, click **Recover** in the `SDPS-B` row.
   - Within one probe interval (5 s or less), `SDPS-B`'s `sysUpTime.0` probe succeeds, moves the device's circuit
     breaker to `HALF_OPEN`, triggers an immediate verification full poll, and restores `SDPS-B` to `✓ OPERATIONAL`
     and `SURV` to `✓ OK`.

### Playbook 4: Maintenance mode, alarm suppression, and equipment commands

Equipment commands are on the board itself: select a tile, and the detail panel shows a **Control** section to
supervisors and administrators. Every command first shows a confirm bar that names the unit and what will happen;
only **Confirm** sends it. The supervisor sends each command to the unit as an SNMP `SET` with the read-write
`operator` user. It records the command in the event log with your username twice: as *issued* before it is sent,
and again with its outcome. If it cannot record the command, it does not send it, and the panel says *The command was
not sent: it could not be recorded*.

1. **Put `FDPS-B` (`STANDBY`) into maintenance:** select the `FDPS-B` tile, click **Maintenance on**, then **Confirm**.
   - **What you see on the board:**
     - `FDPS-B` keeps its `✓ OPERATIONAL` badge and gains the blue `MAINT` badge beside it.
     - Because a unit in maintenance counts as unavailable (a planned outage), `FD` turns `▲ DEGRADED`
       (`Only one FDPS unit available`) and raises alarm `903`.
     - Alarm `103 Maintenance mode on` is raised on `FDPS-B`, but because `FDPS-B` is in maintenance, it is
       suppressed. Tick **`Show suppressed (1)`** in the alarm bar to see it.
     - **Recent events** in the detail panel lists `Maintenance on by supervisor1: issued`, then
       `Maintenance on by supervisor1: done`.
2. **Restart `FDPS-B` while it is in maintenance:** click **Restart**, then **Confirm**.
   - **Restart** is available only in maintenance. The unit itself enforces this too: a restart it receives outside
     maintenance is refused, and the panel shows the reason. Every result in the panel starts with the unit it
     concerns, for example `FDPS-B: Restart done`.
3. **Take `FDPS-B` out of maintenance, then make it active:** click **Maintenance off** and **Confirm**, then
   **Make active** and **Confirm**. **Make active** appears only for the standby unit of a redundant pair, and is
   enabled only while that unit is operational and out of maintenance, which is why it waits for **Maintenance off**.
   - **What you see:** `MAINT` disappears, `FD` returns to `✓ OK`, and `FDPS-B` swaps roles with `FDPS-A` to become
     `ACTIVE`.

### Playbook 5: Full-poll alarm reconciliation (`via RESYNC`) when traps are lost

1. **Tell `RAD-S` to drop all outgoing traps and informs, then fail it:** on the **Simulator** screen, click
   **Drop traps** in the `RAD-S` row, then **Fail**.
2. **What you see:** within at most one 5-second poll interval, `RAD-S` turns `✕ FAILED` and alarms `101`, `201`, and
   `202` appear in the alarm list even though no trap was sent. Click the `RAD-S` tile: **Recent events** in the
   detail panel lists the `ALARM_RAISED` events. The event log records each of them with `via: RESYNC` in its payload,
   because they came from the full poll's alarm-table walk; the board itself does not display the source.

### Playbook 6: Plan maintenance with the Impact screen

1. **Pick the unit:** open **Impact** in the header and tick `FDPS-B` under **Plan maintenance**. The page address now
   carries the plan.
2. **Read the plan's verdict:** `▲ REDUCED MARGIN` for `FD`, which goes from `OK` to `DEGRADED` with the reason
   `Only one FDPS unit available`. `SURV` does not change. No service is lost, but the margin is gone.
3. **Find what that exposes:** in the table, `FDPS-A` now reads `✕ SERVICE LOSS`, because with `FDPS-B` out, one more
   failure leaves no FDPS unit.
4. **Do it from the board:** select `FDPS-B`, click **Maintenance on**. The confirm bar shows the same preview,
   `Impact at … UTC: REDUCED MARGIN - FD: Only one FDPS unit available`. Click **Confirm**, and the board shows `FD`
   `▲ DEGRADED`, as the plan said. On **Impact**, a plan that still names `FDPS-B` now reads `NO SERVICE IMPACT`,
   because the unit is already out and taking it out again changes nothing.
5. **Take it out again:** click **Maintenance off** and **Confirm**. `FD` returns to `✓ OK`. On **Impact**, click
   **Clear** (or open Impact from the header), and `FDPS-A` is back to `▲ REDUCED MARGIN`.

### Playbook 7: Read a flood of alarms from two failed radars

1. **Fail the first radar:** on the **Simulator** screen, click **Fail** in the `RAD-N` row.
   - **What you see:** five new alarms, shown as one row: `RAD-N Equipment failed` with `+4 correlated · 4 unack`.
     No flood yet: five alarms are below the threshold.
2. **Fail the second radar:** click **Fail** in the `RAD-E` row.
   - **What you see:** five more alarms make ten in a few seconds. The amber `ALARM FLOOD` chip appears in the
     header, and the alarm bar says `ALARM FLOOD since …Z · correlated alarms are grouped under their cause`. The
     list has two cause rows, `RAD-N` and `RAD-E`, each with `+4 correlated · 4 unack`.
3. **Read the groups:** click `+4 correlated` on `RAD-N`. Under it are `201` and `202` (`on the failed unit`) and
   the `401` of `SDPS-A` and of `SDPS-B` (`input from RAD-N`). Click the button again to collapse it.
4. **Acknowledge:** tick the checkbox of the `RAD-N` row and click **Ack selected**. The cause and its four alarms go
   to `ACK`, and the unacknowledged count drops by five. The `RAD-E` group is untouched.
5. **Reset:** click **Reset all** on the **Simulator** screen and confirm. The alarms clear. The flood ends once
   fewer than 5 alarms have been raised in the last 10 minutes, so the chip stays for a while.

### Playbook 8: Review an incident with Replay

Run [Playbook 7](#playbook-7-read-a-flood-of-alarms-from-two-failed-radars) first, or use any earlier incident.

1. **Find the alarm:** open **Alarm history**, set the equipment to `RAD-N` and click **Search**. Click **Replay** on
   the `RAD-N Equipment failed` row.
   - **What you see:** the Replay screen under its band, with the play head one minute before the failure. `RAD-N` is
     `OPERATIONAL` and both services are `OK`. The timeline shows the alarm markers and, shaded, the flood.
2. **Step through the failure:** click **Next event** repeatedly.
   - **What you see:** the ticker fills as `RAD-N` goes `FAILED`, the alarms are raised, `SURV` stays `OK`, then
     `RAD-E` fails and `SURV` turns `DEGRADED`. The play head and the band time follow each step.
3. **Play it at speed:** pick `16×` and click **Play**, then **Pause** at any moment. Click a point on the timeline to
   jump there.
4. **Look at one unit:** click the `RAD-N` tile. The detail panel shows its state and values with `sampled …Z`, and no
   command.
5. **Check for gaps:** shaded spans on the timeline are periods with no recorded data, for example while the stack was
   stopped. If there is one in the window, click inside it to see `No recorded data: supervisor not running`.
6. **Export:** open **Export incident report** and choose **CSV**. Open the file: the summary lines come first, then
   one row per event.
7. **Return:** click **Back to live**.

## 4. REST API reference

Every user action has a screen; this reference is for integrators. The read-only history endpoints behind Replay and
the incident export are also described in [Architecture §2.6](architecture.md#26-replay-and-the-incident-export). The
interactive OpenAPI page is at <http://localhost:8080/api/v1/docs>. Every call needs a signed-in session cookie, and
every `POST` and `PATCH` also needs the `X-XSRF-TOKEN` header with the value of the `XSRF-TOKEN` cookie. Refusals are
RFC 9457 problem details.

| Method & Path | Role | Description |
|---|---|---|
| `GET /api/v1/board` | VIEWER | The live board snapshot (`centre`, `serverTime`, `lastEventId`, `services`, `equipment`, open `alarms` with their `correlation`, and the `flood` state). |
| `GET /api/v1/equipment/{id}` | VIEWER | The equipment state and its last 50 events (`recentEvents`); `404` for an unknown ID. |
| `GET /api/v1/alarms` | VIEWER | Alarm search. Params: `state` (`open` \| `all`, default `open`), `severity`, `equipment`, `from` and `to` (ISO-8601 UTC), `page` (`0`), `size` (`1..500`, default `50`). |
| `GET /api/v1/impact?maintenance=<id>` | VIEWER | Read-only impact analysis of the live board. Repeat `maintenance` for each planned unit (none is allowed, duplicates are ignored). Returns `asOf`, `basis`, the `plan` (`units`, `verdict`, `unconfirmed`, each service's `before` and `after`, `notes`), `singlePoints`, `outOfService` and `unknownUnits`. `400` for an id that is not in the inventory. Verdicts are `NO_SERVICE_IMPACT`, `REDUCED_MARGIN` and `SERVICE_LOSS`. |
| `GET /api/v1/history/board?at=<ISO>` | VIEWER | Reconstructed board at instant `at` (`at`, `noData`, `gapThresholdSeconds`, `lastRecordedAt`, `lastRecordedType`, and `board` with each unit's latest 30 s sample and `sampledAt`). |
| `GET /api/v1/history/events?after=<id>&until=<ISO>&limit=<1..1000>` | VIEWER | Paged historical events (`{events, more}`) in id order after `after` up to `until`, mapped to the same message shapes as the live stream plus `sample` and `command`. |
| `GET /api/v1/history/timeline?from=<ISO>&to=<ISO>` | VIEWER | Timeline markers (`ALARM_RAISED`, up to 2,000 newest), alarm `floods`, no-data `gaps`, and supervisor `restarts` for a window of at most 7 days. |
| `GET /api/v1/history/alarms/{id}/raised` | VIEWER | The `occurredAt` timestamp of the newest `ALARM_RAISED` event for alarm `id` (`404` if none), used by the Alarm history **Replay** link. |
| `GET /api/v1/history/export?from=<ISO>&to=<ISO>&format=csv\|json` | VIEWER | Downloads the incident report (`incident-<from>-<to>.csv` or `.json`) containing the incident summary followed by every non-sample event in the window (at most 7 days). |
| `GET /api/v1/stream` | VIEWER | Server-Sent Events (`equipment`, `service`, `alarm`, `flood`, `supervisor` with `id`, plus volatile `telemetry` and `heartbeat`); accepts `Last-Event-ID`. |
| `POST /api/v1/alarms/{id}/ack` | SUPERVISOR | Acknowledges one alarm; optional body `{"comment":"..."}`. |
| `POST /api/v1/alarms/ack` | SUPERVISOR | Acknowledges several alarms; body `{"ids":["..."],"comment":"..."}`. |
| `POST /api/v1/equipment/{id}/commands` | SUPERVISOR | Body `{"command":"MAINTENANCE_ON"}` (or `MAINTENANCE_OFF`, `RESTART`, `MAKE_ACTIVE`). `200` with the outcome, `409` when the unit refuses, `504` when it does not answer (the outcome is then uncertain; a command is never retried), `502` when the command cannot be sent. |
| `GET /api/v1/simulator/devices` | SUPERVISOR | Demo mode only: the simulated devices with their state and faults. |
| `POST /api/v1/simulator/devices/{id}/faults` | SUPERVISOR | Demo mode only: body `{"fault":"FAIL","target":null}`; `SENSOR_LOST` needs a radar as `target`. |
| `POST /api/v1/simulator/reset` | SUPERVISOR | Demo mode only: clears every fault and restores the default roles (`204`). |
| `GET /api/v1/admin/users` | ADMIN | The accounts with role and enabled state. |
| `PATCH /api/v1/admin/users/{username}` | ADMIN | Body `{"role":"SUPERVISOR"}` and/or `{"enabled":false}`; ends that user's sessions. `409` for your own account. |
| `POST /api/v1/admin/users/{username}/password` | ADMIN | Body `{"password":"..."}` under the registration rule; ends that user's sessions (`204`). |

## 5. Health checks, metrics and troubleshooting

### 5.1 Querying supervisor Actuator metrics (`:8081`)

Inside the `supervisor` container, Spring Boot Actuator runs on loopback port `8081`. You can read health and
Micrometer metrics through Docker Compose:

```sh
# Check supervisor health
docker compose exec supervisor bash -c \
  'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && cat <&3'

# Check connected SSE clients, circuit breaker states, or stale poll discards
docker compose exec supervisor bash -c \
  'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/metrics/resilience4j.circuitbreaker.state HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && cat <&3'
```

Useful metric names under `/actuator/metrics/<name>`:

- `resilience4j.circuitbreaker.state`: per-device circuit breaker state (`closed`, `open`, `half_open`).
- `atmsup.poll.rejected`: full polls skipped because the device's circuit breaker was `OPEN`.
- `atmsup.poll.coalesced`: overlapping full polls coalesced by single-flight scheduling.
- `atmsup.board.stale.results`: in-flight probe or poll results discarded because their generation
  (`contactGeneration` or `statusGeneration`) was outdated.
- `atmsup.sse.clients` / `atmsup.sse.disconnects`: active SSE streams and overflow or error disconnections.
- `atm_snapshot_failures_total`: failed `board_snapshot` writes (best-effort; never affects the live board).

### 5.2 Common troubleshooting questions

| Symptom | Cause | Solution |
|---|---|---|
| `Port 8080 is in use by another process.` during preflight | Another local server or container is bound to TCP `8080`. | Run `lsof -nP -iTCP:8080 -sTCP:LISTEN` to find and stop the process. |
| `Invalid authentication protocol specified after -a flag: SHA-256` when running `snmpget` on macOS | The `/usr/bin/snmpget` shipped with macOS is net-snmp 5.6, which lacks `SHA-256`. | Install Homebrew net-snmp (`brew install net-snmp` and use `$(brew --prefix net-snmp)/bin/snmpget`) or run the container command in the README's [Talking to an agent with net-snmp](../README.md#talking-to-an-agent-with-net-snmp) section. |
| Old `CLEARED-UNACK` alarms stay in the alarm list after **Reset all** on the Simulator screen | By design: an unacknowledged alarm stays in `CLEARED-UNACK` after the fault clears so fleeting faults are never lost. | Click **Ack all visible** on the board to close them, or run `scripts/demo-preflight.sh --fresh` to wipe the database volume. |
| A device stays `UNKNOWN` after recovering from `UNREACHABLE` | If contact returned while a `HALF_OPEN` trial poll was already in flight, verification can wait for the breaker's 30 s timeout. The device stays `UNKNOWN` meanwhile. | No action needed: it recovers within about 30 s. |
