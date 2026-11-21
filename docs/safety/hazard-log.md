# Hazard log

This is an engineering hazard log for ATM Supervisor. It is **not** a safety case and does not follow
a formal safety assessment method. It records the ways a supervision tool can mislead its operator,
what the current release does about each one, and which tests show it. ATM Supervisor is a
non-operational prototype; see the [README](../../README.md).

Status values:

- **Mitigated:** the release has a mitigation and a test that exercises it.
- **Partly mitigated:** a mitigation exists, but a known gap remains.
- **Open:** no mitigation yet.
- **Not applicable yet:** the feature that creates the hazard is not built yet.

| ID | Hazard | Status |
|---|---|---|
| [H1](#h1-stale-observations-shown-as-current) | Stale observations shown as current | Mitigated |
| [H2](#h2-lost-traps-hide-device-alarms) | Lost traps hide device alarms | Mitigated |
| [H3](#h3-circuit-breaker-state-masks-contact-loss) | Circuit-breaker state masks contact loss | Mitigated, one known delay |
| [H4](#h4-unknown-presented-as-healthy) | `UNKNOWN` presented as healthy | Mitigated |
| [H5](#h5-a-slow-browser-blocks-supervision) | A slow browser blocks supervision | Partly mitigated |
| [H6](#h6-partial-or-concurrent-snmp-set) | Partial or concurrent SNMP SET | Mitigated in the simulator |
| [H7](#h7-a-supervisor-restart-loses-operational-context) | A supervisor restart loses operational context | Mitigated |
| [H8](#h8-database-or-writer-overload) | Database or writer overload | Partly mitigated |
| [H9](#h9-an-alarm-flood-hides-a-critical-alarm) | An alarm flood hides a critical alarm | Mitigated |
| [H10](#h10-v3-traps-rejected-after-an-agent-restart) | SNMPv3 traps rejected after an agent restart | Mitigated |
| [H11](#h11-hazards-of-planned-features) | Unsafe commands, unnoticed authentication failures, a restarted device shown with its old state | Not applicable yet |
| [H12](#h12-an-alarm-acknowledged-by-someone-not-entitled-to) | An alarm acknowledged by someone not entitled to | Mitigated |
| [H13](#h13-a-command-sent-to-the-wrong-unit-or-at-the-wrong-time) | A command sent to the wrong unit or at the wrong time | Mitigated |
| [H14](#h14-a-training-fault-reaches-a-live-centre) | A training fault reaches a live centre | Mitigated |
| [H15](#h15-a-what-if-verdict-taken-as-clearance-or-used-when-stale) | A what-if verdict taken as clearance or used when stale | Mitigated |
| [H16](#h16-correlation-hides-or-misstates-an-alarm) | Correlation hides or misstates an alarm | Mitigated |
| [H17](#h17-replay-mistaken-for-live) | Replay mistaken for live | Mitigated |

## H1 Stale observations shown as current

- **Cause:** a poll result that was started before a newer fact arrived (a trap, a lost contact) is
  applied after it; or the board keeps showing values after polling has stopped working.
- **Consequence:** the operator sees an old state as the current one.
- **Mitigation:**
  - Every result carries the generation it was started under, and the [board][BoardDecider] discards
    a result whose generation is no longer current. There are two generations per device: a full poll
    is checked against the status generation (bumped by traps and contact changes), a probe against the
    contact generation (bumped by contact changes only).
  - Three failed probes make the device `UNKNOWN (LOST_CONTACT)`; probes that succeed while full polls
    keep failing make it `UNKNOWN (STATUS_UNAVAILABLE)` after 3 poll intervals ([StatusDeriver]).
  - The UI shows the age of a value in amber after 2 poll intervals, and a "DATA NOT LIVE" overlay when
    the event stream is down.
- **Verified by:** [BoardDeciderTest] (`staleSuccessIsDiscarded`, `staleFailureIsDiscarded`),
  [BoardWriterTest] (`aTrapStillDiscardsAnOlderFullPoll`, `aContactChangeStillDiscardsAnOlderProbe`),
  [StatusDeriverTest] (`probesOkButFullPollsFailingBecomeStatusUnavailable`), [freshness.spec.ts],
  [equipment-tile.spec.ts], [data-not-live-overlay.spec.ts].
- **Status:** mitigated. Under a trap flood that outpaces a full poll's round trip, full polls are
  discarded as stale and the device becomes `UNKNOWN (STATUS_UNAVAILABLE)`. That is the safe direction.

## H2 Lost traps hide device alarms

- **Cause:** traps travel over UDP and can be lost; an agent can also stop sending them.
- **Consequence:** an active device alarm never reaches the board, or a cleared one stays.
- **Mitigation:**
  - Traps are hints. Each one triggers an immediate re-poll, and polling is the source of truth.
  - Every successful full poll walks the device's alarm table and reconciles the open alarms against it
    in one transaction ([AlarmEngine] `reconcile`).
  - A poll whose alarm-table walk is missing or incomplete fails as a whole, so a partial walk never
    clears an alarm ([ObservationMapper]).
  - Critical device alarms are sent as acknowledged INFORMs rather than traps.
- **Verified by:** [AlarmEngineTest] (one test per reconciliation case), [BoardDeciderTest]
  (`aPollReconcilesDeviceAlarmsInOneDecision`, `aStaleAlarmWalkChangesNoAlarms`), [ObservationMapperTest]
  (`aMissingAlarmWalkFailsTheObservation`), [PollSchedulerTest] (`aWalkThatFailsMidwaySubmitsOnlyPollFailed`),
  and end to end with real UDP agents in [SupervisorEndToEndIT] (`trapLossIsReconciledFromTheAlarmTable`).
- **Status:** mitigated.

## H3 Circuit-breaker state masks contact loss

- **Cause:** a circuit breaker that guards every SNMP request turns its own fast failures into
  "lost contact", or keeps a device looking unreachable after it has come back.
- **Consequence:** contact state reflects the breaker, not the network.
- **Mitigation:**
  - Lightweight probes (`sysUpTime.0`) never go through the breaker and are the only authority for
    contact ([PollScheduler]). The per-device breaker ([DeviceBreakers]) guards full polls only, and a
    full-poll failure never touches contact.
  - When a probe restores contact, an open breaker moves to half-open and one verification poll runs
    at once.
  - A trap cannot invalidate contact evidence, because probes are checked against their own generation.
- **Verified by:** [PollSchedulerTest] (`probesContinueWhileBreakerIsOpen`,
  `contactRestoredMovesAnOpenBreakerToHalfOpenAndVerifiesAtOnce`), [StatusDeriverTest]
  (`fullPollFailureNeverTouchesContact`), [BoardWriterTest] (`trapFloodCannotMaskLostContact`).
- **Status:** mitigated. Known delay: if contact is restored while a half-open trial poll is already
  running, verification can wait up to about 30 s. The device stays `UNKNOWN` meanwhile.

## H4 `UNKNOWN` presented as healthy

- **Cause:** a device or service whose state cannot be confirmed is drawn with its last known value.
- **Consequence:** the operator trusts a green tile that nobody has verified.
- **Mitigation:**
  - `UNKNOWN` overrides the last known health and always carries a reason.
  - Services are evaluated twice, with unknown equipment treated as available and as unavailable
    ([ServiceEvaluator]). If the results differ, the service is `UNKNOWN` with its best case.
  - Supervisor alarms 903 and 904 follow confirmed facts only. An unconfirmed redundancy fact leaves
    903 as it is.
  - `UNKNOWN` is never green: it is hatched and violet. Open alarms of an unknown device carry an
    "unconfirmed" marker.
- **Verified by:** [ServiceEvaluatorTest], [BoardDeciderTest]
  (`aConfirmedRedundancyLossSurvivesTheFailedUnitGoingUnknown`, `anUnknownSdpsBesideAConfirmedDegradationRaisesNo903`),
  [status-badge.spec.ts] (`UNKNOWN is never green`), [alarm-list.spec.ts]
  (`marks an alarm unconfirmed while its equipment is UNKNOWN`).
- **Status:** mitigated.

## H5 A slow browser blocks supervision

- **Cause:** the component that applies state changes also writes to browser connections, and one
  slow or half-open connection stalls it.
- **Consequence:** supervision stops for everyone because of one client.
- **Mitigation:**
  - The board writer never does network I/O. After a commit it offers the events to each client
    without blocking ([SseBroadcaster]).
  - Each client has a bounded queue (256 events by default). A client that overflows it is
    disconnected, never silently skipped, and resumes with `Last-Event-ID` from a 1,000-event ring
    buffer or gets a full resync ([SseClient]).
  - Telemetry is coalesced per device, so it cannot fill the queue.
- **Verified by:** [SseBroadcasterTest] (`slowClientDoesNotDelayOthers`, `overflowDisconnectsInsteadOfDropping`),
  [SseClientTest] (`offersNeverBlockAndAFullQueueRefusesTheNextDurableMessage`, `telemetryIsCoalescedLatestOnly`).
- **Status:** partly mitigated. The number of connected clients is not bounded yet.

## H6 Partial or concurrent SNMP SET

- **Cause:** an agent checks and applies a multi-binding SET one binding at a time while other
  requests read or change the same device.
- **Consequence:** a reader sees a half-applied change, or a SET is applied on top of a state it was
  not checked against.
- **Mitigation:** the simulator checks and applies every binding of a SET under one lock and publishes
  the result to its MIB views once, after the last binding ([Centre]).
- **Verified by:** [CentreTest] (`aSetRequestHoldsTheCentreLockFromFirstCheckToLastApply`,
  `aMultiBindingSetReachesTheViewsOnlyOnceItIsComplete`, `aSetThatFailsMidwayStillPublishesWhatItChanged`),
  [DeviceAgentSetIT].
- **Status:** mitigated in the simulator. The supervisor sends each command as a single-binding SET and never
  retries it (H13); interlocks are planned (H11), and the impact of maintenance is previewed (H15).

## H7 A supervisor restart loses operational context

- **Cause:** the supervisor restarts and comes back with an empty or outdated picture.
- **Consequence:** open alarms disappear, or old states are shown as current.
- **Mitigation:**
  - Every state change is stored as an event, together with the alarm rows, in one PostgreSQL transaction
    ([JpaBoardStore]).
  - On start the supervisor rebuilds its board from the log: the newest board snapshot plus a fold of every later
    event, or a fold of the whole log when there is no snapshot ([BoardWriter], [BoardHistory]). That keeps open
    alarms, each device's last known maintenance flag and role, and its last contact time. Then it marks every device
    `UNKNOWN (SUPERVISOR_RESTARTED)` since the restart until its first poll. That poll reconciles the alarms.
  - A heartbeat event every 60 s and a stop event on graceful shutdown record when supervision ran.
- **Verified by:** [BoardWriterTest] (`restartLoadsOpenAlarmsAndMarksSupervisorRestarted`,
  `restartKeepsLastKnownMaintenanceAndRole`, `heartbeatTicksAreCommitted`), [ReplayFidelityIT]
  (`aRestartRebuildsTheSameBoardFromTheLog`).
- **Status:** mitigated. There is one supervisor instance; while it is down, nothing is supervised.

## H8 Database or writer overload

- **Cause:** the database is down or slow, or inputs arrive faster than the single board writer can
  commit them.
- **Consequence:** the board falls behind or shows state that was never stored.
- **Mitigation:**
  - The board only applies what was committed. A failed commit leaves the board unchanged and is
    retried, and it still invalidates the results it made stale ([BoardWriter]).
  - Poll concurrency is bounded (8 by default), and stable values are stored as one sample per device
    every 30 s, not on every poll.
  - An equipment command is never sent unless its intent is stored first, and no single wait on the writer lasts
    more than 3 s, so 6 s for a command's two records ([EquipmentControl], H13). While the database is down,
    commands are refused with 503.
- **Verified by:** [BoardWriterTest] (`commitFailureLeavesBoardUnchangedAndRetries`,
  `failedCommitStillBumpsGenerationsAndRequestsTheRepoll`, `commitFailureDoesNotLoseTheTelemetrySample`),
  [PollDispatcherTest], [EquipmentControlTest] (`anIntentTheStoreCannotCommitIsNeverSent`,
  `anIntentNotRecordedInTimeIsNeverSentAndIsFollowedByNotSent`).
- **Status:** partly mitigated. There is no explicit "storage down" signal yet (the tiles turn stale),
  the writer's input queue is not bounded, and the event log has no retention purge yet.

## H9 An alarm flood hides a critical alarm

- **Cause:** many alarms arrive at once.
- **Consequence:** the operator misses the one that matters.
- **Mitigation:**
  - the alarm list is ordered by severity, then time;
  - escalation makes an acknowledged alarm unacknowledged again;
  - alarms of a device in maintenance are recorded but hidden by default;
  - alarms that a known failure explains are grouped under their cause, so one row stands for the incident (see
    [H16](#h16-correlation-hides-or-misstates-an-alarm) for what keeps the grouping from hiding an alarm);
  - the board page says `ALARM FLOOD` in its header and above the alarm list from 10 new alarms in 10 minutes
    until fewer than 5, so the operator knows to read the cause rows first. The state is stored as events and
    cannot flap between the two thresholds.
- **Verified by:** [alarm-list.spec.ts] (`alarm list orders by severity then time and counts unacknowledged`,
  `showsTheFloodBannerWhileTheFloodIsActive`, `groupsCorrelatedAlarmsUnderTheirActiveCause`,
  `groupsSortByTheirWorstSeverity`), [AlarmEngineTest] (`escalationReUnacknowledges`,
  `raisedDuringMaintenanceIsSuppressed`), [BoardDeciderTest] (`theFloodStartsAtTheThresholdRaise`,
  `theFloodDoesNotFlapBetweenTheThresholds`, `suppressedRaisesAreNotCounted`), [BoardReducerTest]
  (`supervisorStartedEndsTheFlood`), [BoardWriterTest] (`aFailedCommitLeavesTheFloodWindowAsItWas`) and
  [app-header.spec.ts] (`showsTheFloodChipOnTheLiveBoard`, `showsNoFloodChipOffTheBoard`).
- **Status:** mitigated. An audible alarm is planned.

## H10 v3 traps rejected after an agent restart

- **Cause:** SNMPv3 timeliness checks reject notifications from an agent whose engine boot counter
  went backwards after a restart.
- **Consequence:** traps from that agent are silently dropped.
- **Mitigation:** each simulated agent persists an ever-growing `snmpEngineBoots` counter
  ([EngineBootsStore]).
- **Verified by:** [EngineBootsPersistenceIT] (`trapAfterRestartWithHigherBootsIsAccepted`,
  `trapAfterRestartWithLowerBootsIsRejected`).
- **Status:** mitigated.

## H11 Hazards of planned features

These come from the [target design](../design/2026-11-13-atm-supervisor-design.md) and apply once
the features exist. "Replay mistaken for live" is built and now has its own entry, [H17](#h17-replay-mistaken-for-live):

- **Unintended or unsafe control command:** roles, a confirm step, SETs without retries and an uncertain outcome
  are in place (H13), and **Maintenance on** shows an impact preview computed by the same service evaluator, which
  informs and never blocks (H15). Still planned: a mandatory reason, interlocks, and an impact preview for the other
  commands.
- **Failed SNMP authentication goes unnoticed:** a supervisor alarm on USM authentication reports.
- **A restarted device shown with its pre-restart state:** `UNKNOWN (RESTARTING)` until the first
  poll after a commanded restart, with the restart proven through `sysUpTime`.

## H12 An alarm acknowledged by someone not entitled to

- **Cause:** anyone who can reach the board acknowledges alarms, or an acknowledgement does not record who made it.
- **Consequence:** an alarm disappears from the unacknowledged list without a responsible person, and the record
  cannot show who accepted it.
- **Mitigation:**
  - Every API call, the live stream included, needs a signed-in user ([SecurityConfig]). Refusals are problem
    details, never redirects.
  - Acknowledging needs SUPERVISOR or ADMIN (`@PreAuthorize` on [AlarmController]), and the acknowledgement event
    records the signed-in username.
  - New accounts are read-only VIEWERs. CSRF protection covers every state-changing request, sign-in included.
  - Demo accounts with their default passwords cannot exist outside demo mode ([DemoAccountGuard]).
- **Verified by:** [SecurityRulesTest] (`aViewerCannotAcknowledge`, `aSupervisorAcknowledgesAsThemself`,
  `aPostWithoutCsrfIsRefused`), [SupervisorEndToEndIT] (`anonymousRequestsAreRefused`, `signInNeedsTheCsrfToken`,
  `aViewerCannotAcknowledge`), [AccountServiceIT].
- **Status:** mitigated. The UI also hides the controls for viewers, but the server is what enforces the rule.

## H13 A command sent to the wrong unit or at the wrong time

- **Cause:** a click on the wrong tile or button, a double click, or a command that does not fit the unit's state
  (a restart outside maintenance, making an active unit active).
- **Consequence:** a working unit goes out of service, or a pair switches over during traffic.
- **Mitigation:**
  - Commands need SUPERVISOR or ADMIN, on the server ([CommandController]).
  - Every command first shows a confirm bar that names the unit and what will happen; only Confirm sends it, and the
    controls are disabled until the answer arrives, so a double click sends one SET.
  - The panel offers a command only while the unit's state allows it (Make active for an operational standby out of
    maintenance, Restart in maintenance). Restart is also greyed out while the board shows a restart in progress,
    but this is not relied on: the unit stops answering moments after a restart starts, so a poll seldom sees it,
    and a second restart then gets the uncertain no-answer outcome. A confirm bar disappears once its command no
    longer fits the unit, and every result names the unit it concerns, even if another unit was selected meanwhile.
  - The agent refuses a command that does not fit the unit's state (`inconsistentValue`), and the panel shows the
    rule of that command. Any other error status is reported as a fault of the operator user's access on the unit,
    and a SET that never left the supervisor as not sent, so neither is blamed on the unit's state.
  - A SET is never retried, and no answer is reported as an uncertain outcome to check before sending again.
  - Every command is recorded with the username twice: its intent before the SET (`issued`) and its outcome after
    it ([EquipmentControl]). A command whose intent is not stored within 3 s is not sent (503), and `not sent` is
    recorded after the intent. An outcome that cannot be stored is still shown to the supervisor and logged as an
    error; the intent stays in the event log.
- **Verified by:** [EquipmentControlTest] (`theIntentIsRecordedBeforeTheSetAndTheOutcomeAfterIt`,
  `anIntentTheStoreCannotCommitIsNeverSent`, `aStoppedWriterMeansNothingIsSent`,
  `anIntentNotRecordedInTimeIsNeverSentAndIsFollowedByNotSent`, `inconsistentValueIsARefusalThatGivesTheRuleOfTheCommand`,
  `anyOtherErrorStatusIsAFailureOfTheOperatorUsersAccessNotARefusal`), [CommandControllerTest], [SnmpClientIT]
  (`aSetTheAgentRefusesCarriesItsErrorStatus`), [SupervisorEndToEndIT]
  (`aSupervisorPutsAUnitIntoMaintenanceAndTheCommandIsLogged`), and the UI spec `equipment-control.spec.ts`.
- **Status:** mitigated.

## H14 A training fault reaches a live centre

- **Cause:** the simulator console is reachable in a deployment that supervises real equipment.
- **Consequence:** an operator injects what they believe is a training fault, or someone unauthenticated does.
- **Mitigation:**
  - The console exists only with `atm.demo-mode=true` ([SimulatorConsoleController]); outside demo mode its API
    answers 404, the screen is not linked, and its address leads to the board.
  - It needs a signed-in supervisor, and nginx no longer exposes the simulator.
  - Demo credentials and demo accounts are refused outside demo mode.
- **Verified by:** [SimulatorConsoleAbsentTest], [SimulatorConsoleControllerTest] (`aViewerCannotInjectFaults`), and
  the UI spec `demo.guard.spec.ts`.
- **Status:** mitigated.

## H15 A what-if verdict taken as clearance or used when stale

- **Cause:** a planned-maintenance or single-point-of-failure verdict is read as a statement that an action is
  acceptable, or it is acted on after the board has changed, or it hides that a unit's state is not confirmed.
- **Consequence:** a unit is taken out when the board no longer supports it, or a service loses its margin because a
  verdict looked like a clearance.
- **Mitigation:**
  - The verdicts are `NO SERVICE IMPACT`, `REDUCED MARGIN` and `SERVICE LOSS`. No verdict is worded as a
    clearance or an approval, and `NO SERVICE IMPACT` is shown in neutral text, never the OK green. The verdict is text, not
    colour alone.
  - Every result states its basis, "Derived from the supervised equipment and the service rules; not a safety
    assessment", and the time of the answer. The Impact screen refreshes every 5 s, keeps the last result
    with `Last refresh failed at …` when a refresh fails, and drops an answer to a superseded request. The
    **Maintenance on** preview also asks again every 5 s while it is armed, states the time of its answer, and adds
    `(last refresh failed)` to the last answer when a refresh fails; a request that hangs for 4 s counts as failed.
  - With units in `UNKNOWN` state, a verdict is the worst over every possible state of those units, marked
    unconfirmed when the states disagree, with the units named. The unit asked about, or planned, counts as working
    now. It is exact up to 10 `UNKNOWN` units, and beyond that a conservative bound, which can only overstate.
  - The analysis calls the same [ServiceEvaluator] as the board, through `assess` for the statuses it shows and
    `evaluateWorld` for its verdicts, so it cannot contradict the board. It reads a snapshot of the board and writes
    nothing.
  - The preview informs and never blocks: a person decides. Interlocks stay planned (H11).
  - The rules count available units only. Switchover transients and radar coverage are not modelled, and the basis
    line says the verdict is not a safety assessment.
- **Verified by:** [ServiceEvaluatorAssessTest] (`withNothingExtraItAgreesWithEvaluate`,
  `unknownStillSplitsThePasses`, `aWorldWithNoUnknownUnitUpIsThePessimisticPassAndWithAllUpTheOptimistic`,
  `aWorldSettlesEachUnknownUnitAndDownWins`), [ImpactAnalyzerTest]
  (`allHealthyRadarsHaveNoImpactAndProcessorsReduceMargin`, `aFailedPartnerMakesTheOtherASinglePoint`,
  `planningTheStandbyReducesMarginAndExposesTheActive`, `anUnknownUnitGivesTheWorstCaseUnconfirmed`,
  `unknownSurveillanceUnitsMakeTheLastConfirmedRadarASinglePoint`, `twoUnknownRadarsMakeEachOfThemReduceTheMargin`,
  `anUnknownPairMakesEachOfItsUnitsASinglePoint`, `aCentreThatIsAllUnknownGivesEveryRadarTheWorstCase`,
  `aboveTheCapTheBoundIsNeverBetterThanTheExactAnswer`, `planningTheActiveUnitNotesTheSwitchover`),
  [ImpactControllerTest] (`aViewerGetsTheReport`, `signedOutIs401`, `anUnknownUnitIs400`), [SupervisorEndToEndIT]
  (`thePredictedImpactOfMaintenanceIsWhatTheBoardShows`), and the UI specs [impact-page.spec.ts]
  (`neverSaysSafe`, `noServiceImpactIsNotGreen`, `anUnconfirmedVerdictNamesTheUnknownUnits`,
  `aConfirmedPlanWithAnUnconfirmedSinglePointStillNamesTheUnknownUnits`, `anUnknownSideShowsItsBestCase`,
  `refreshesEveryFiveSecondsAndKeepsTheLastResultOnFailure`, `aRefreshThatHangsCountsAsFailedAndIsAnnouncedPolitely`,
  `onlyTheLatestRequestLands`, `aFourHundredClearsTheShownReport`) and
  [equipment-control.spec.ts] (`maintenanceOnShowsTheImpactPreview`, `confirmStaysEnabledWhileTheImpactLoads`,
  `whileArmedThePreviewRefreshesAndShowsTheNewVerdict`, `aFailedRefreshKeepsTheLastAnswerAndSaysSo`,
  `aPreviewThatHangsFailsAfterFourSeconds`).
- **Status:** mitigated for Maintenance on and the Impact screen. A verdict is only as rich as the service rules.

## H16 Correlation hides or misstates an alarm

- **Cause:** an alarm is grouped under another, so it is out of sight in the list; or a link is wrong.
- **Consequence:** the operator misses an alarm, or blames the wrong unit.
- **Mitigation:**
  - Only three named topology rules link alarms (`SENSOR_INPUT`, `SWITCHOVER` and `SAME_UNIT`), and an alarm's shape
    alone picks its rule, so the order in which traps arrive never changes a link ([AlarmCorrelator]).
  - A correlated alarm is never more severe than its cause. Escalation of the alarm above its cause, or a cause
    that de-escalates or is re-raised below it, detaches the link.
  - An alarm is listed under its cause only while the cause is active and shown. If the cause is cleared, closed or
    hidden by the suppressed filter, the alarm is a top-level row.
  - A collapsed group shows how many alarms are correlated and how many are unacknowledged, and the count in the
    alarm bar includes every alarm. Selecting a collapsed group, or **Ack all visible**, acknowledges every alarm in
    it, each on its own as before.
  - Service alarms (`903`, `904`) and lost contact (`901`) are never correlated and never causes.
  - The link is recorded when it is made, with the cause's equipment and code, so history shows the last link recorded on
    the alarm and replay can rebuild the grouping at any moment from the stored events. Nothing recomputes it at read time.
- **Verified by:** [AlarmCorrelatorTest] (`theShapeAloneChoosesTheRule`, `aMoreSevereAlarmIsNotCorrelated`,
  `escalationAboveTheCauseDetaches`, `aCauseThatDeEscalatesDetachesTheMoreSevere`,
  `aReRaisedCauseAtLowerSeverityDetachesTheMoreSevere`, `supervisorAlarmsAreNeverCorrelatedOrCauses`,
  `aLateCauseAdoptsItsAlarms`, `aClearedCauseIsNoCause`), [BoardDeciderTest]
  (`aCauseFirstAndACorrelatedFirstGiveTheSameLinks`, `aResyncChangeKeepsALinkMadeEarlierInTheSameDecision`),
  [EventCodecTest] (`anAlarmPayloadWithoutCorrelationDecodesAsUncorrelated`), [JpaBoardStoreIT]
  (`openAlarmsLoadWithTheirCorrelation`) and [alarm-list.spec.ts] (`aCorrelatedAlarmOfAClearedCauseIsTopLevel`,
  `aCorrelatedAlarmOfAHiddenCauseIsTopLevel`, `theCollapsedRowCountsUnacknowledgedCorrelatedAlarms`,
  `selectingACollapsedGroupAcknowledgesTheWholeGroup`, `ackAllVisibleIncludesCollapsedCorrelatedAlarms`).
- **Status:** mitigated. The rules cover radars, SDPS and FDPS only; they grow with the device types.

## H17 Replay mistaken for live

- **Cause:** a past board is read as the present, or something live leaks into the replay screen.
- **Consequence:** wrong decisions, or a live alarm missed because the screen shown was history.
- **Mitigation:**
  - Replay has a store of its own that reads only the history API. Nothing it uses injects the live stream or the live
    board store, and a live message arriving during replay changes nothing ([ReplayStore]).
  - A band at the top of the page cannot be dismissed. It reads `REPLAY · <HH:MM:SSZ> · NOT LIVE · values ≤30 s · no
    freshness ages`, with the date when the window spans more than one UTC day.
  - The page has no controls: no acknowledgement, no equipment command, no live header and no link to a control
    screen. The detail panel does not fetch the equipment's live data.
  - A period when the supervisor was not running shows `No recorded data: supervisor not running`, never a quiet green
    board. One gap rule, 1.5 × the heartbeat interval, is used by the seek, the timeline, the summary and playback
    ([GapRule]).
  - Values are marked as samples (`sampled HH:MM:SSZ`), are never interpolated, and carry no freshness age. A sample
    from before a restart is not shown after it.
  - The playback clock never runs past the events that have been loaded: it waits for the next page, and it stops on a
    failed load instead of showing a board it has not rebuilt.
  - The board at an instant is the fold of the same events with the same reducer as live, so replay cannot show a
    board that live did not show ([ReplayFidelityIT]).
- **Verified by:** [replay-store.spec.ts] (`liveStreamMessagesChangeNothingInReplay`, `theClockWaitsForTheNextPage`,
  `aFailedSeekBlocksPlaybackUntilTheNextSuccessfulSeek`, `aGapLongerThanTheThresholdTurnsNoDataOn`,
  `afterASeekTheGapStartsOneThresholdAfterTheLastRecordedEvent`, `aStopTurnsNoDataOnAtOnce`,
  `aSupervisorStartedMessageReloadsTheBoardAtThatInstant`), [replay-page.spec.ts] (`rendersNoAppHeaderAndNoControlLinks`,
  `theAlarmListIsReadOnly`, `theDetailPanelHasNoEquipmentControl`, `theGapOverlayShowsTheExactCopy`),
  [replay-band.spec.ts] (`showsTheExactBandCopy`, `cannotBeDismissed`), [GapRuleTest]
  (`exactlyTheThresholdIsNoData`, `aStopStartsTheGapAtOnce`), [BoardHistoryIT] (`boardAtDuringDowntimeIsNoData`,
  `aSampleBeforeTheRestartIsNotShownAfterIt`) and [ReplayFidelityIT] (`theLiveBoardEqualsBoardAtNowAndTheFoldFromTheStart`,
  `aRestartRebuildsTheSameBoardFromTheLog`).
- **Status:** mitigated. Nothing stops a person from reading the band wrongly or leaving the page open for hours;
  the band stays on screen for that reason, and a shared replay link opens under the same band.

[CommandController]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/CommandController.java
[EquipmentControl]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/control/EquipmentControl.java
[SimulatorConsoleController]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/simconsole/SimulatorConsoleController.java
[EquipmentControlTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/control/EquipmentControlTest.java
[CommandControllerTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/api/CommandControllerTest.java
[SimulatorConsoleControllerTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/simconsole/SimulatorConsoleControllerTest.java
[SimulatorConsoleAbsentTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/simconsole/SimulatorConsoleAbsentTest.java
[AlarmEngine]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[AlarmController]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/AlarmController.java
[SecurityConfig]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/security/SecurityConfig.java
[DemoAccountGuard]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/security/DemoAccountGuard.java
[SecurityRulesTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/security/SecurityRulesTest.java
[AccountServiceIT]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/security/AccountServiceIT.java
[BoardDecider]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[BoardWriter]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[DeviceBreakers]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/DeviceBreakers.java
[JpaBoardStore]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStore.java
[ObservationMapper]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/snmp/ObservationMapper.java
[PollScheduler]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[ServiceEvaluator]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluator.java
[SseBroadcaster]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseBroadcaster.java
[SseClient]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/api/SseClient.java
[StatusDeriver]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StatusDeriver.java
[Centre]: ../../simulator/src/main/java/io/github/selvitopu/atmsup/simulator/centre/Centre.java
[EngineBootsStore]: ../../inventory/src/main/java/io/github/selvitopu/atmsup/inventory/EngineBootsStore.java
[AlarmEngineTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngineTest.java
[BoardDeciderTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardDeciderTest.java
[BoardWriterTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriterTest.java
[JpaBoardStoreIT]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/JpaBoardStoreIT.java
[ObservationMapperTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/snmp/ObservationMapperTest.java
[PollDispatcherTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/polling/PollDispatcherTest.java
[PollSchedulerTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/polling/PollSchedulerTest.java
[ReplayFidelityIT]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/ReplayFidelityIT.java
[ServiceEvaluatorTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluatorTest.java
[SseBroadcasterTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/api/SseBroadcasterTest.java
[SseClientTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/api/SseClientTest.java
[StatusDeriverTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/StatusDeriverTest.java
[SupervisorEndToEndIT]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorEndToEndIT.java
[CentreTest]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/centre/CentreTest.java
[DeviceAgentSetIT]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/agent/DeviceAgentSetIT.java
[EngineBootsPersistenceIT]: ../../simulator/src/test/java/io/github/selvitopu/atmsup/simulator/agent/EngineBootsPersistenceIT.java
[alarm-list.spec.ts]: ../../ui/src/app/features/alarms/alarm-list/alarm-list.spec.ts
[data-not-live-overlay.spec.ts]: ../../ui/src/app/features/board/data-not-live-overlay/data-not-live-overlay.spec.ts
[equipment-tile.spec.ts]: ../../ui/src/app/features/board/equipment-tile/equipment-tile.spec.ts
[freshness.spec.ts]: ../../ui/src/app/core/state/freshness.spec.ts
[status-badge.spec.ts]: ../../ui/src/app/features/board/status-badge/status-badge.spec.ts
[SnmpClientIT]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/snmp/SnmpClientIT.java
[ServiceEvaluatorAssessTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluatorAssessTest.java
[ImpactAnalyzerTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/impact/ImpactAnalyzerTest.java
[ImpactControllerTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/api/ImpactControllerTest.java
[impact-page.spec.ts]: ../../ui/src/app/features/impact/impact-page/impact-page.spec.ts
[equipment-control.spec.ts]: ../../ui/src/app/features/board/equipment-control/equipment-control.spec.ts
[AlarmCorrelator]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmCorrelator.java
[AlarmCorrelatorTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmCorrelatorTest.java
[BoardReducerTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardReducerTest.java
[EventCodecTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/EventCodecTest.java
[app-header.spec.ts]: ../../ui/src/app/features/board/app-header/app-header.spec.ts
[BoardHistory]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/BoardHistory.java
[GapRule]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/history/GapRule.java
[GapRuleTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/GapRuleTest.java
[BoardHistoryIT]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/history/BoardHistoryIT.java
[ReplayStore]: ../../ui/src/app/features/replay/replay-store.ts
[replay-store.spec.ts]: ../../ui/src/app/features/replay/replay-store.spec.ts
[replay-page.spec.ts]: ../../ui/src/app/features/replay/replay-page/replay-page.spec.ts
[replay-band.spec.ts]: ../../ui/src/app/features/replay/replay-band/replay-band.spec.ts
