# Change Request CR-001: Automatic A/B Switchover Visibility and Confirmed Service Redundancy Alarm

| Field | Value |
|---|---|
| ID | `CR-001` |
| Title | Make automatic 3-second A/B switchover and single-chain redundancy loss explicit on the board |
| Status | Implemented |
| Affected modules | `mib`, `simulator`, `supervisor::board`, `supervisor::alarms`, `ui` |
| Related hazards | [H1 (Stale observations)](hazard-log.md#h1-stale-observations-shown-as-current), [H2 (Lost traps)](hazard-log.md#h2-lost-traps-hide-device-alarms), [H4 (`UNKNOWN` presented as healthy)](hazard-log.md#h4-unknown-presented-as-healthy) |

## 1. Description

In Demo ACC, the surveillance tracker pair (`SDPS-A` / `SDPS-B`) and the flight data processor pair (`FDPS-A` /
`FDPS-B`) run as active/standby pairs ([`RedundantPair`]). When the `active` unit fails and the `standby` partner is
usable (`OPERATIONAL` or `DEGRADED`, and not in maintenance), the pair switches over automatically after 3 seconds.

If the supervisor only updates the `ACTIVE` and `STANDBY` role badges when a pair switches over, two operational
problems follow:

1. **Unnoticed automatic switchover:** a switchover that happens while the System Controller is looking elsewhere
   leaves no trace in the alarm list once the standby takes over.
2. **False confidence or false alarm on single-chain operation:** after failover, the service is still working on one
   unit, so its redundancy is gone (`DEGRADED` with supervisor alarm `903` *Service redundancy lost*). Conversely, if
   the standby unit is `UNKNOWN (LOST_CONTACT)` while the service is already `DEGRADED` for another reason (such as
   two failed radars), the supervisor must not report redundancy loss as a confirmed fact or raise alarm `903`.

## 2. Impact analysis

| Component | Change |
|---|---|
| MIB ([`DEMO-ATM-TECHSUP-MIB.txt`]) | Defines notification `atmTsSwitchover` (`1.3.6.1.4.1.32473.1.2.0.4`, carrying `atmTsEqId` and `atmTsEqRole`) and device alarm `104` *Switchover occurred* (`warning(6)`). |
| Simulator ([`RedundantPair`], [`Centre`]) | Schedules automatic switchover 3 s after the active unit enters `FAILED` (if the standby is usable). On switchover, swaps roles (`standby -> active`), raises alarm `104` in the new active unit's [`AlarmTable`], and emits `atmTsSwitchover` and `atmTsAlarmRaised`. Also supports idempotent `becomeActive(2)` via SNMP `SET` on the standby unit. |
| Supervisor ([`SurveillanceRule`], [`FlightDataRule`], [`ServiceEvaluator`]) | Sets `redundancyLost = true` and degrades the service (`DEGRADED`, reason `"Only one SDPS/FDPS unit available"`) when only one unit of the pair is available. In [`ServiceEvaluator`], `redundancyConfirmed` is `best.redundancyLost() == worst.redundancyLost()`, so an `UNKNOWN` standby unit never causes a false redundancy-lost alarm. |
| Supervisor ([`AlarmEngine`], [`BoardDecider`]) | Raises supervisor alarm `903` (*Service redundancy lost*, `MAJOR`) when `redundancyLost` is confirmed (`true`), clears it when confirmed `false`, and leaves an existing `903` unchanged while `redundancyConfirmed` is `false`. Reconciles device alarm `104` both from traps and from every full poll's alarm-table walk (`Via.RESYNC`). |
| UI (`ui/`) | Displays each unit's `ACTIVE` / `STANDBY` role badge on the equipment tile, lists the service's confirmed and `"Unconfirmed: …"` reasons, and shows alarms `104` and `903` in the alarm list. |

## 3. Safety assessment

- **[H2 (Lost traps hide device alarms)](hazard-log.md#h2-lost-traps-hide-device-alarms):** even if the
  `atmTsSwitchover` and `atmTsAlarmRaised` (104) UDP traps are dropped, the next full poll (within 5 s) reads the
  swapped `atmTsEqRole` scalars and walks `atmTsAlarmTable`, raising alarm `104` (`via RESYNC`) and alarm `903`.
- **[H4 (`UNKNOWN` presented as healthy)](hazard-log.md#h4-unknown-presented-as-healthy):**
  - If `SDPS-A` is `FAILED` (confirmed) and `SDPS-B` is `OPERATIONAL`, both passes report `redundancyLost = true`, so
    `SURV` is `DEGRADED` and alarm `903` is raised. Even if `SDPS-A` later loses contact (`UNKNOWN`), the confirmed
    redundancy loss on the remaining single unit survives
    (`aConfirmedRedundancyLossSurvivesTheFailedUnitGoingUnknown`).
  - If `RAD-N` and `RAD-E` are `FAILED` (making `SURV` `DEGRADED`) and `SDPS-B` goes `UNKNOWN`, the optimistic pass
    has 2 SDPS units (`redundancyLost = false`) while the pessimistic pass has 1 (`redundancyLost = true`).
    `ServiceEvaluator` reports `"Fewer than 2 radars available"` plus
    `"Unconfirmed: Only one SDPS unit available (equipment in UNKNOWN state: SDPS-B)"`, and raises no alarm `903`
    (`anUnknownSdpsBesideAConfirmedDegradationRaisesNo903`).
- **[H7 (Supervisor restart loses context)](hazard-log.md#h7-a-supervisor-restart-loses-operational-context):** on
  restart, the start-up board is the fold of the event log ([`BoardHistory`]), so a pair that had already switched over
  keeps its last-known `active`/`standby` roles while awaiting its first poll.

## 4. Effort estimate

| Work item | Effort |
|---|---|
| Simulator [`RedundantPair`] 3 s timer, alarm `104`, and `atmTsSwitchover` emission | 2 h |
| [`ServiceEvaluator`] confirmed vs. unconfirmed `redundancyLost` and reason formatting | 3 h |
| [`AlarmEngine`] service alarm `903` handling when `redundancyConfirmed` is `false` | 2 h |
| Unit and integration tests | 3 h |
| Total | 10 h |

## 5. Test evidence

- [`RedundantPairTest`] and [`CentreTest`]: automatic switchover 3 s after the active unit fails, no switchover when
  the standby is failed or in maintenance, alarm `104` raised on the new active unit, and idempotent `becomeActive`
  SET handling.
- [`ServiceEvaluatorTest`]: `"SDPS-A failed, RAD-N and RAD-E unknown"` (`redundancyLost = true`) vs.
  `"RAD-N and RAD-E failed, SDPS-B unknown"` (`redundancyLost = false`, with
  `"Unconfirmed: Only one SDPS unit available (equipment in UNKNOWN state: SDPS-B)"`).
- [`BoardDeciderTest`]: `aConfirmedRedundancyLossSurvivesTheFailedUnitGoingUnknown` and
  `anUnknownSdpsBesideAConfirmedDegradationRaisesNo903`.
- End-to-end automatic failover is exercised in the [demo walkthrough](../demo-v0.1.md).

[`DEMO-ATM-TECHSUP-MIB.txt`]: ../../mib/DEMO-ATM-TECHSUP-MIB.txt
[`RedundantPair`]: ../../simulator/src/main/java/io/github/selvitopu/atmsup/simulator/model/RedundantPair.java
[`AlarmTable`]: ../../simulator/src/main/java/io/github/selvitopu/atmsup/simulator/model/AlarmTable.java
[`Centre`]: ../../simulator/src/main/java/io/github/selvitopu/atmsup/simulator/centre/Centre.java
[`SurveillanceRule`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/SurveillanceRule.java
[`FlightDataRule`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/FlightDataRule.java
[`ServiceEvaluator`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluator.java
[`AlarmEngine`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[`BoardDecider`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[`BoardHistory`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/BoardHistory.java
[`RedundantPairTest`]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/model/RedundantPairTest.java
[`CentreTest`]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/centre/CentreTest.java
[`ServiceEvaluatorTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluatorTest.java
[`BoardDeciderTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardDeciderTest.java
