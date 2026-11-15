# MIB guide

The simulated devices speak SNMP through one custom MIB,
[`DEMO-ATM-TECHSUP-MIB`](../mib/DEMO-ATM-TECHSUP-MIB.txt), plus the standard SNMPv2-MIB `system`
group. This page is a tour of the MIB and of what the agents in this release actually serve.

The MIB lives under enterprise number **32473**, which RFC 5612 reserves for documentation, so it
cannot collide with a real vendor's OIDs. CI lints it with `smilint` ([`mib/lint.sh`](../mib/lint.sh)),
and the Java OID constants in [`AtmTsOids`](../mib/src/main/java/io/github/selvitopu/atmsup/mib/AtmTsOids.java)
are tested against it.

## The tree

The MIB already defines the groups of every planned device type. The simulator implements the
groups of the three device types in this release.

| OID under `1.3.6.1.4.1.32473.1` | Name | Contents | Served now |
|---|---|---|---|
| `.1.1` | `atmTsEquipment` | Scalars every device has: id, type, health, role, maintenance, command | yes |
| `.1.2` | `atmTsAlarms` | The table of active alarms, and the alarm identity carried in notifications | yes |
| `.1.3` | `atmTsRadar` | Rotation period, plots per scan, transmitter channels A and B | yes |
| `.1.4` | `atmTsAdsb` | ADS-B gateway values | not yet |
| `.1.5` | `atmTsSdps` | Track count, latency, and the sensor input table | yes |
| `.1.6` | `atmTsFdps` | Flight plan count, message queue depth, latency | yes |
| `.1.7` | `atmTsVcs` | Voice communication system values | not yet |
| `.1.8` | `atmTsRadio` | The frequency table of a radio site | not yet |
| `.1.9` | `atmTsRecorder` | Recorder channels and disk usage | not yet |
| `.2.0` | `atmTsNotificationsPrefix` | The four notifications | yes |
| `.3` | `atmTsConformance` | Compliance statement and object groups | — |

## Health is not maintenance

Two textual conventions carry the domain model:

- `AtmTsEquipmentStatus`: `operational(1)`, `degraded(2)`, `failed(3)`. A device reports only these
  three. `UNKNOWN` exists only in the supervisor, when it cannot confirm a device's state; see the
  [domain primer](domain.md#unknown-belongs-to-the-supervisor).
- `AtmTsSeverity`: `cleared(1)`, `indeterminate(2)`, `critical(3)`, `major(4)`, `minor(5)`, `warning(6)`.

Maintenance is a separate `TruthValue`, `atmTsEqMaintenanceMode`, so a device in maintenance still
reports its real health.

## Equipment scalars (`atmTsEquipment`, `.1.1`)

| Object | Access | Notes |
|---|---|---|
| `atmTsEqId` | read-only | The inventory id, for example `RAD-N` |
| `atmTsEqType` | read-only | `radar(1)` … `recorder(7)` |
| `atmTsEqStatus` | read-only | `AtmTsEquipmentStatus` |
| `atmTsEqRole` | read-only | `standalone(1)`, `active(2)`, `standby(3)` |
| `atmTsEqStatusChangedAt` | read-only | `DateAndTime` of the last health change |
| `atmTsEqSwVersion` | read-only | Software version string |
| `atmTsEqMaintenanceMode` | read-write | `true(1)` while in maintenance |
| `atmTsEqCommand` | read-write | Write `becomeActive(2)`, `restart(3)` or `resetCounters(4)`; reads return `none(1)` |
| `atmTsEqLastCommandResult` | read-only | `none(1)`, `inProgress(2)`, `success(3)`, `rejected(4)` |

`becomeActive` is idempotent: on the standby unit of a pair it swaps the roles, and on the unit that
is already active it succeeds without doing anything. The simulator refuses `restart` with
`inconsistentValue` unless the device is in maintenance.

## The alarm table (`atmTsAlarms`, `.1.2`)

`atmTsAlarmTable` lists the alarms that are active on the device right now. Its rows are indexed by
`atmTsAlarmCode` and `atmTsAlarmInstance`, so a row's OID ends in `.<code>.<instance>`, and each row
has four readable columns: instance name, severity, text and `atmTsAlarmRaisedAt`.

The supervisor walks this table on every successful full poll and reconciles its open alarms
against it. That is what makes a lost trap harmless; see
[H2 in the hazard log](safety/hazard-log.md#h2-lost-traps-hide-device-alarms). A poll whose table walk
is missing or incomplete fails as a whole, so a partial walk never clears an alarm.

Index columns cannot appear in a notification, so `atmTsNotifAlarmCode` and `atmTsNotifAlarmInstance`
(`accessible-for-notify`) carry the alarm's identity in the alarm notifications.

## Device values

| Group | Objects |
|---|---|
| Radar (`.1.3`) | `atmTsRadarRotationPeriodMs`, `atmTsRadarPlotsPerScan`, `atmTsRadarActiveChannel`, `atmTsRadarChannelAStatus`, `atmTsRadarChannelBStatus` |
| SDPS (`.1.5`) | `atmTsSdpsTrackCount`, `atmTsSdpsLatencyMs`, and `atmTsSdpsSensorTable`: one row per sensor with its name and input status, `receiving(1)`, `lost(2)` or `excluded(3)` |
| FDPS (`.1.6`) | `atmTsFdpsFlightPlanCount`, `atmTsFdpsQueueDepth`, `atmTsFdpsLatencyMs` |

## Notifications (`.2.0`)

| Notification | Objects | Sent when |
|---|---|---|
| `atmTsEqStatusChange` | `atmTsEqId`, `atmTsEqStatus`, `atmTsEqRole` | Health changes |
| `atmTsAlarmRaised` | `atmTsEqId`, `atmTsNotifAlarmCode`, `atmTsNotifAlarmInstance`, `atmTsAlarmInstanceName`, `atmTsAlarmSeverity`, `atmTsAlarmText` | An alarm is raised or its severity changes |
| `atmTsAlarmCleared` | `atmTsEqId`, `atmTsNotifAlarmCode`, `atmTsNotifAlarmInstance` | An alarm clears |
| `atmTsSwitchover` | `atmTsEqId`, `atmTsEqRole` | The standby unit of a pair took over |

Agents send notifications to the supervisor on UDP 10162: critical alarms as acknowledged INFORMs,
everything else as TRAPs. The supervisor treats every notification as a hint. It applies an alarm at
once, and always re-polls the device to confirm.

## Security

Every agent is SNMPv3 with USM `authPriv`: SHA-256 authentication and AES-128 privacy. Each device
has a fixed engine ID built from its device id (the RFC 3411 text format) and persists its
`snmpEngineBoots` counter, so its v3 notifications stay acceptable after a restart.

| User | Demo passwords (auth / priv) | Rights |
|---|---|---|
| `monitor` | `monitor-auth-demo` / `monitor-priv-demo` | Read (`GET`, `GETNEXT`, `GETBULK`); sends notifications |
| `operator` | `operator-auth-demo` / `operator-priv-demo` | Read, and `SET` on `atmTsEqMaintenanceMode` and `atmTsEqCommand` |

The passwords are demo defaults: the applications refuse them outside demo mode, and the
`SNMP_*` environment variables listed in the [README](../README.md#demo-mode-and-ports) override them.
A `SET` from `monitor` fails with `noAccess`.

## Trying it

The agents use SHA-256, which needs net-snmp 5.8 or later. The examples assume the demo stack is up
(see the [README](../README.md#quickstart)) and run from the repository root. With a local net-snmp:

```sh
snmpbulkwalk -v3 -l authPriv -u monitor -a SHA-256 -A monitor-auth-demo -x AES -X monitor-priv-demo \
  -M +./mib -m +DEMO-ATM-TECHSUP-MIB localhost:16101 DEMO-ATM-TECHSUP-MIB::atmTechSupMIB
```

Or from a throwaway container on the Compose network, with nothing installed locally:

```sh
docker run --rm --network atm-supervisor_default -v "$PWD/mib":/mib:ro alpine:3.22 sh -c '
  apk add -q net-snmp-tools &&
  snmpbulkwalk -v3 -l authPriv -u monitor -a SHA-256 -A monitor-auth-demo -x AES -X monitor-priv-demo \
    -M +/mib:/usr/share/snmp/mibs -m +DEMO-ATM-TECHSUP-MIB simulator:16101 DEMO-ATM-TECHSUP-MIB::atmTechSupMIB'
```

Output from RAD-N (UDP 16101):

```text
DEMO-ATM-TECHSUP-MIB::atmTsEqId.0 = STRING: RAD-N
DEMO-ATM-TECHSUP-MIB::atmTsEqType.0 = INTEGER: radar(1)
DEMO-ATM-TECHSUP-MIB::atmTsEqStatus.0 = INTEGER: operational(1)
DEMO-ATM-TECHSUP-MIB::atmTsEqRole.0 = INTEGER: standalone(1)
DEMO-ATM-TECHSUP-MIB::atmTsEqStatusChangedAt.0 = STRING: 2026-11-15,15:24:18.4,+0:0
DEMO-ATM-TECHSUP-MIB::atmTsEqSwVersion.0 = STRING: demo-1.0.0
DEMO-ATM-TECHSUP-MIB::atmTsEqMaintenanceMode.0 = INTEGER: false(2)
DEMO-ATM-TECHSUP-MIB::atmTsEqCommand.0 = INTEGER: none(1)
DEMO-ATM-TECHSUP-MIB::atmTsEqLastCommandResult.0 = INTEGER: none(1)
DEMO-ATM-TECHSUP-MIB::atmTsRadarRotationPeriodMs.0 = Gauge32: 4473 milliseconds
DEMO-ATM-TECHSUP-MIB::atmTsRadarPlotsPerScan.0 = Gauge32: 308
DEMO-ATM-TECHSUP-MIB::atmTsRadarActiveChannel.0 = INTEGER: channelA(1)
DEMO-ATM-TECHSUP-MIB::atmTsRadarChannelAStatus.0 = INTEGER: operational(1)
DEMO-ATM-TECHSUP-MIB::atmTsRadarChannelBStatus.0 = INTEGER: operational(1)
```

With `CHANNEL_A_FAIL` injected into RAD-E (UDP 16102), its alarm table holds 102 *Equipment
degraded* and 201 *Channel A failed*, both at instance 0:

```text
DEMO-ATM-TECHSUP-MIB::atmTsAlarmSeverity.102.0 = INTEGER: minor(5)
DEMO-ATM-TECHSUP-MIB::atmTsAlarmSeverity.201.0 = INTEGER: minor(5)
DEMO-ATM-TECHSUP-MIB::atmTsAlarmText.102.0 = STRING: Equipment degraded
DEMO-ATM-TECHSUP-MIB::atmTsAlarmText.201.0 = STRING: Channel A failed
DEMO-ATM-TECHSUP-MIB::atmTsAlarmRaisedAt.102.0 = STRING: 2026-11-15,15:24:50.7,+0:0
DEMO-ATM-TECHSUP-MIB::atmTsAlarmRaisedAt.201.0 = STRING: 2026-11-15,15:24:50.7,+0:0
```

SDPS-A (UDP 16104) shows its sensor input table under `DEMO-ATM-TECHSUP-MIB::atmTsSdps`, one row per
radar. Use `snmpset` with the `operator` user to switch maintenance on, for example on FDPS-A
(UDP 16106):

```sh
snmpset -v3 -l authPriv -u operator -a SHA-256 -A operator-auth-demo -x AES -X operator-priv-demo \
  -M +./mib -m +DEMO-ATM-TECHSUP-MIB localhost:16106 DEMO-ATM-TECHSUP-MIB::atmTsEqMaintenanceMode.0 i 1
```

A `SET` from the command line goes straight to the agent and bypasses the supervisor. The supervisor
sees the change at its next poll. Control commands through the supervisor, with interlocks and
outcome tracking, are planned.
