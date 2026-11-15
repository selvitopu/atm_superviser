# ADR-0008: Independent Health Probes, Two-Generation Single-Flight Polling, Confirmed Service Facts, and Crash-Safe `UNCERTAIN` Commands

* **Status:** Accepted — Independent probes, `HALF_OPEN` verification on contact restore, two poll generations, and confirmed-only service facts implemented in `v0.1.1`; crash-safe supervisor commands (`UNCERTAIN`) planned for `v0.3`
* **Date:** 2026-11-14 (revised 2026-11-15 for `v0.1.1` hardening)
* **Deciders:** Project maintainers

## Context and Problem Statement

Two rounds of architectural review ([`ATM-Supervisor-Architecture-Review-Decisions.md`](../refactor/ATM-Supervisor-Architecture-Review-Decisions.md) and the `v0.1.1` hardening review) exposed five subtle failure modes in polling, state derivation, and control:

1. **Circuit breaker masking contact or delaying recovery ([Hazard H3](../safety/hazard-log.md#h3-circuit-breaker-state-masks-contact-loss)):** If a per-device circuit breaker gates reachability checks, its fast-failures look like lost contact; and when a device recovers while its full-poll breaker is `OPEN`, waiting out the breaker timer leaves the device stuck in `UNKNOWN (AWAITING_STATUS)` for 30 s.
2. **Stale full polls overwriting newer state ([Hazard H1](../safety/hazard-log.md#h1-stale-observations-shown-as-current)):** A slow full poll started before a trap or contact loss can finish after it and overwrite the newer state.
3. **Trap floods masking lost contact ([Hazard H1](../safety/hazard-log.md#h1-stale-observations-shown-as-current) / [H3](../safety/hazard-log.md#h3-circuit-breaker-state-masks-contact-loss)):** If probes and full polls share a single generation counter bumped by traps, a device that emits traps while its request/response path is dead discards every `ProbeFailed` and never reaches `UNKNOWN (LOST_CONTACT)`.
4. **Unconfirmed pessimistic details asserted as fact ([Hazard H4](../safety/hazard-log.md#h4-unknown-presented-as-healthy)):** When a service rule's optimistic and pessimistic passes return the same status (e.g. `SURV = DEGRADED` because two radars failed while `SDPS-B` is merely `UNKNOWN`), reporting the pessimistic pass's `redundancyLost = true` falsely raises supervisor alarm `903` (*Service redundancy lost*) for an unconfirmed unit.
5. **Ambiguous post-timeout / post-crash control commands ([Hazard H11](../safety/hazard-log.md#h11-hazards-of-planned-features)):** Retrying an SNMP `SET` after a timeout can cause a duplicate execution, while marking a timed-out `SET` as `FAILED` misleads the operator if the device actually applied it.

## Decision Outcome

We adopt five architectural invariants across polling, board evaluation, and command execution:

### 1. Independent Lightweight Probes & Immediate `HALF_OPEN` Verification (`v0.1.1`)

* **Probes never go through the circuit breaker:** [`PollScheduler`] runs lightweight `sysUpTime.0` probes on their own schedule outside [`DeviceBreakers`]. Probes alone govern `reachable`, `consecutiveFailures`, `UNKNOWN (LOST_CONTACT)`, and alarm `901`.
* **Full polls are guarded by [`DeviceBreakers`]:** Count-based sliding window of 5 calls (minimum 3, 50% failure rate, 30 s wait, 1 permitted call in `HALF_OPEN`, no automatic time-based `OPEN → HALF_OPEN`). A full-poll failure never touches contact. If probes succeed while full polls fail for more than 3 poll intervals, [`StatusDeriver`] sets health to `UNKNOWN (STATUS_UNAVAILABLE)`.
* **Immediate verification on contact restore:** When a probe restores contact, [`BoardDecider`] emits `RepollRequested(id, Reason.CONTACT_RESTORED)`. [`PollScheduler.onRepoll`] moves an `OPEN` breaker to `HALF_OPEN` and triggers an immediate single-flight full poll so the device recovers to `OPERATIONAL` within seconds. (A `Reason.TRAP` repoll leaves an `OPEN` breaker open.)

### 2. Single-Flight Full Polls & Two Generations per Device (`v0.1.1`)

* **Single-flight coalescing:** [`PollScheduler`] runs at most one full poll per device at a time. Overlapping scheduled polls coalesce (`atmsup.poll.coalesced`), and traps arriving during an active poll coalesce into a single follow-up refresh poll.
* **Two generations per device in [`LiveBoard`] ([`GenerationSource`]):**
  * **`statusGeneration` (`generation(id)`):** Bumped by traps (`invalidated`) and by contact changes. Full-poll results (`PollSucceeded`, `PollFailed`) carry the status generation they started under and are discarded if `< live.generation(id)`.
  * **`contactGeneration` (`contactGeneration(id)`):** Bumped by contact changes **only** (`contactInvalidated`). Probe results (`ProbeSucceeded`, `ProbeFailed`) carry the contact generation they started under and are discarded only if `< live.contactGeneration(id)`.
  * **Why two generations:** A probe carries only contact evidence (`sysUpTime.0`), never subsystem status. Keeping `contactGeneration` independent of traps ensures a trap storm can never discard `ProbeFailed` results and mask `LOST_CONTACT`.
* **Failed commits still bump generations:** In [`BoardWriter`], even if `store.commit` throws, the generations the decision invalidates are still bumped (a trap bumps the status generation; a contact change bumps both) and `RepollRequested` is published, so results made stale by that input can never be accepted later.

### 3. Confirmed-Only Service Facts & Alarm `903` Protection (`v0.1.1`)

* In [`ServiceEvaluator`], when the optimistic (`best`) and pessimistic (`worst`) passes produce the same [`ServiceStatus`]:
  * The service state uses `best.redundancyLost()` and `best.reasons()`, because only those are confirmed facts.
  * Any extra reasons present only in `worst` are collapsed into a single `"Unconfirmed: <reasons> (equipment in UNKNOWN state: <ids>)"` entry.
  * `ServiceState.redundancyUnconfirmed` is `true` when `best.redundancyLost() != worst.redundancyLost()`. [`BoardDecider`] then passes no redundancy fact to [`AlarmEngine`], so alarm `903` (*Service redundancy lost*) is neither raised nor cleared.

### 4. Crash-Safe Commands with `active_command` Exclusion and `UNCERTAIN` (`v0.3`)

* Every supervisor command (`SWITCHOVER`, `MAINTENANCE_ON`, `MAINTENANCE_OFF`, `RESTART`, `RESET_COUNTERS`) will carry an `Idempotency-Key` + `request_fingerprint`, claim durable exclusion in `active_command(equipment_id PRIMARY KEY)` (locking both pair members in alphabetical order for `SWITCHOVER`) inside the initial `COMMAND_ISSUED` transaction, and send the SNMP `SET` with **0 retries**.
* Timeout or post-crash recovery sets the command status to **`UNCERTAIN`** and reconciles by polling only, keeping `active_command` locked until polling confirms the state or a supervisor records an audited manual resolution.

### Consequences

* **Good:** Contact recovery after `UNREACHABLE` completes within one probe interval instead of waiting 30 s for the breaker timer.
* **Good:** Trap floods cannot mask a dead request path, and unconfirmed equipment never triggers false `903` service redundancy alarms.
* **Known limitation in `v0.1.1`:** If contact returns while a `HALF_OPEN` trial poll is already in flight, verification can still wait up to ~30 s while the device remains safely `UNKNOWN`.

## Verification

* [`PollSchedulerTest`] — Verifies `probesContinueWhileBreakerIsOpen`, `contactRestoredMovesAnOpenBreakerToHalfOpenAndVerifiesAtOnce`, `failedVerificationPollReopensTheBreaker`, `trapRepollLeavesAnOpenBreakerOpen`, `contactRestoredDuringAnActivePollVerifiesOnceAfterIt`, and `probesCarryTheContactGenerationAndFullPollsTheStatusGeneration`.
* [`BoardWriterTest`] — Verifies `trapFloodCannotMaskLostContact`, `aContactChangeStillDiscardsAnOlderProbe`, `aTrapStillDiscardsAnOlderFullPoll`, and `failedCommitStillBumpsGenerationsAndRequestsTheRepoll`.
* [`ServiceEvaluatorTest`] & [`BoardDeciderTest`] — Verify confirmed vs. `"Unconfirmed: …"` reasons and `anUnknownSdpsBesideAConfirmedDegradationRaisesNo903`.

[PollScheduler]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[PollScheduler.onRepoll]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[DeviceBreakers]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/DeviceBreakers.java
[StatusDeriver]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StatusDeriver.java
[BoardDecider]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[BoardWriter]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[LiveBoard]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/LiveBoard.java
[GenerationSource]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/GenerationSource.java
[ServiceEvaluator]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluator.java
[ServiceStatus]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceStatus.java
[AlarmEngine]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[PollSchedulerTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/polling/PollSchedulerTest.java
[BoardWriterTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriterTest.java
[ServiceEvaluatorTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluatorTest.java
[BoardDeciderTest]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardDeciderTest.java
[`AlarmEngine`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/alarms/AlarmEngine.java
[`BoardDeciderTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardDeciderTest.java
[`BoardDecider`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardDecider.java
[`BoardWriterTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriterTest.java
[`BoardWriter`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/BoardWriter.java
[`DeviceBreakers`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/DeviceBreakers.java
[`GenerationSource`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/GenerationSource.java
[`LiveBoard`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/LiveBoard.java
[`PollScheduler.onRepoll`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[`PollSchedulerTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/polling/PollSchedulerTest.java
[`PollScheduler`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/polling/PollScheduler.java
[`ServiceEvaluatorTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluatorTest.java
[`ServiceEvaluator`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/ServiceEvaluator.java
[`StatusDeriver`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/board/StatusDeriver.java
