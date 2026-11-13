# ATM Supervisor — Architecture Review Decisions
Date: 2026-11-13
Status: Agreed changes for incorporation into `ATM Supervisor: Design`
Scope: Single-instance Spring Boot supervisor, 15 simulated SNMP devices, MySQL, Angular/SSE. Keep the demo self-contained; no Redis, Kafka or distributed-lock service.

This document records the decisions agreed during the technical review. It is an amendment, not a claim that the original design file or source code has already been modified. Section numbers refer to the original design. Project documentation, API names and code remain in English.

## 1. Circuit Breaker with independent health probes

**Decision:** Include a per-device Circuit Breaker (Resilience4j). Never route recurring health probes through the breaker.

Protect potentially expensive full-status collection (GETBULK / table walks) from repeatedly targeting a failing device. Lightweight availability probes run at the configured cadence (normally 5 s) regardless of breaker state (`CLOSED`, `OPEN`, `HALF_OPEN`). The breaker must not short-circuit these probes.

Drive `reachable`, consecutive failed probes, `UNKNOWN`, and alarm 901 `LOST_CONTACT` exclusively from actual contact attempts, never from calls rejected by an open breaker. Breaker-open is not evidence of device health.

A successful lightweight probe demonstrates reachability only. Permit a limited full-status verification in `HALF_OPEN` and close the breaker only after the relevant full collection succeeds. Do not show `OPERATIONAL` merely because a lightweight OID responded.

Keep circuit state completely distinct from device health and maintenance. During a commanded `RESTART`, retain the existing `RESTARTING` / `UNKNOWN` presentation and command verification rules; do not raise a spurious contact-loss alarm for the expected restart window.

Expose breaker state via Micrometer metrics and Actuator.

**Update:** §§5.1, 6.1, 8 (polling, state derivation), 10, 11.

**Tests:** breaker opens after configured failures; probes continue while open; a failed probe increments real failure counts; successful probe does not turn the UI green; limited full poll verifies recovery and closes the breaker; restart window does not raise 901.

## 2. Per-device single-flight polling, trap refresh and stale-result protection

**Decision:** At most one active full-status poll per device, plus explicit invalidation of stale observations via a per-device generation counter. The single board-writer thread orders inputs but does not by itself guarantee observation freshness.

Every device has a single-flight coordinator for full polls. A scheduled poll arriving during an active full poll is coalesced. A TRAP/INFORM requests a high-priority refresh: if a full poll is active, set `refreshPending` and initiate exactly one follow-up full poll when the active one terminates. Multiple traps coalesce into that single refresh.

Maintain a monotonically increasing generation per device. Increment the generation on any invalidating event (trap, contact change, command result, or explicit refresh request). A full-poll or alarm-table walk result created under an older generation must be discarded. A stale success must never clear an alarm or reset failure counters.

Lightweight probes remain independent of the Circuit Breaker but their effects on `reachable` / failure counters are applied only through the board writer and are subject to the same generation check. A positive probe must not overwrite a newer confirmed subsystem failure.

Incomplete alarm-table walks make no changes. Preserve bounded concurrency and per-request deadlines so a trap flood cannot start unbounded work.

**Update:** §§6.1, 8 (polling and board writer), 8.2, 11.

**Tests:** scheduled poll + trap while in flight; multiple simultaneous traps; older result arriving after invalidation; stale alarm walk after newer observation; timeout followed by recovery; breaker-open with uninterrupted lightweight probes.

## 3. Crash-safe operator commands: idempotency and durable exclusion

**Decision:** MySQL is the sole authority for command idempotency and device-command exclusion. No Redis is required.

**REST request identity**
Angular creates one `Idempotency-Key` (UUID) per operator action and reuses it for retries of that same action. Persist the key and a request fingerprint on the `command` record under a database UNIQUE constraint. A repeat with the same key and same fingerprint returns the existing `commandId` and current status. Reusing a key for a different payload is a conflict.

**Durable device exclusion**
Add table `active_command(equipment_id PRIMARY KEY, command_id)` with a foreign key to `command`. In one short transaction: validate the latest interlocks, insert the command and its `COMMAND_ISSUED` audit event, and claim the required `active_command` row(s). For `SWITCHOVER`, claim both members of the pair in a consistent order in the same transaction.

A competing distinct action receives HTTP 409. A retry using a matching idempotency key returns the original result. Do not hold a MySQL transaction open during SNMP I/O. Commit the intent and exclusion before attempting a SET. Recheck relevant interlocks against the latest board state immediately before dispatch.

**SNMP uncertainty and restart recovery**
Send non-idempotent SNMP SET with zero retries. Durably record that dispatch may be attempted before the network call. On Supervisor restart, load unfinished commands from MySQL and verify postconditions by polling only; never re-issue SETs.

- Positive readback of success → `COMPLETED`
- Positive rejection by the device → appropriate failed/rejected state
- Verification window expires without positive confirmation → `UNCERTAIN` / `UNKNOWN_OUTCOME`

Do not silently release the exclusion for an `UNCERTAIN` command. Provide an audited manual resolution path that can release the device. Normal health probes continue regardless of command exclusion.

Do not claim exactly-once device execution: without agent-side command IDs, SNMP-level exactly-once effects cannot be guaranteed.

**Update:** §§6.1, 8.1, 8.3, 8.4, 8.5, 11. In §8.3, replace any statement that an `UNCERTAIN` command becomes `FAILED` merely because the verification window expired.

**Tests:** double-click and HTTP retry with same key; same key/different body; two different keys for one device; simultaneous commands on a redundant pair; crash before SET, after SET and before response; recovery reconciliation; timeout with ambiguous device outcome; active-command release only after safe resolution.

## 4. SSE backpressure without blocking the board writer

**Decision:** Separate committed state processing from network delivery; bound per-client buffering.

The board writer performs its normal derive → single database transaction → in-memory reducer sequence. After commit it enqueues notifications for asynchronous delivery; it must never block on any SSE client.

Give each SSE client a bounded outgoing queue (initial configurable size 256). For volatile non-durable `telemetry` events, coalesce by equipment ID so a slow client receives only the newest sample. Never silently discard durable alarm, service-state or command events while leaving the connection apparently synchronized. If a client cannot keep up, disconnect it and require catch-up/resynchronization.

Preserve event ordering and SSE `id = event_log.id` for durable events. On reconnect, use the existing `Last-Event-ID` / in-memory 1 000-event ring when the gap is covered; otherwise send `resync` and have Angular fetch `/board`.

Monitor queue saturation, client disconnects and resync activity via metrics. A full client queue or blocked client must not stall database commits or grow memory without bound.

**Update:** §§8.1, 8.5, 9, 10, 11.

**Tests:** one slow SSE client while other clients stay current; queue overflow leads to reconnection, not event loss masquerading as successful delivery; telemetry coalescing; ordered durable event IDs; ring-buffer miss leads to full `/board` resync; board-writer latency remains bounded.

## 5. Event log versus replay: durable state fidelity and sampled telemetry

**Decision:** Persist critical operational changes with full fidelity; treat numerical telemetry as sampled history. Do not claim that replay reconstructs unpersisted five-second values.

**Durable event stream:** Equipment health, role and maintenance changes, contact transitions, service-state changes, alarms, acknowledgments and commands are recorded when they change, with event IDs and the full state needed for deterministic historical reconstruction. `UNKNOWN` / contact loss must never be coalesced away.

**Live telemetry:** Every successful normal poll may publish its numeric measurements over the existing non-ID `telemetry` SSE channel (nominally every 5 s).

**Historical telemetry:** Persist one real sample per equipment every 30 seconds while successful observations are available, even if numerical values changed by less than 5 %. Remove the previous 5 % deadband. Use event type `TELEMETRY_SAMPLED` (or clearly redefine the existing `VALUES_CHANGED` event) to distinguish a timed sample from a business-state transition.

**Replay semantics:** At time T, display the most recent actually recorded sample at or before T, together with its measurement timestamp and age. Do not interpolate or suggest that the value was observed at T. If collection stopped, indicate the gap and retain the existing `UNKNOWN` / `NO_DATA` protections.

`Live Board == fold(event_log)` applies only to the durable operational state, not to every volatile numerical metric. State-change events and snapshots must not claim to carry all intervening unsampled values.

The 30-second interval is a sampling target, not a guarantee of a sample from an unreachable device. No fabricated data fills missing observations.

**Update:** §§5.1, 8.1, 8.4, 8.5, 9, 11.

**Tests:** stable metrics still get a 30-second sample; all health/alarm changes between two samples appear in replay; missed polls produce no synthetic sample; replay shows latest genuine sample plus source timestamp; `NO_DATA` periods remain clearly marked. Separate assertions for durable-state fidelity versus telemetry sampling fidelity.

## 6. Consolidated implementation checklist

- **Polling:** per-device Circuit Breaker (Resilience4j) + independent probes; single-flight full polls; coalesced priority refresh; per-device generation checks for polls and resync; bounded SNMP concurrency.
- **Control:** idempotency key + request fingerprint on `command`; `active_command` table; persist intent and audit atomically; SNMP SET after commit with zero retries; reconcile incomplete commands on recovery; retain indeterminate results safely with audited manual resolution.
- **Streaming:** asynchronous fan-out after commit; bounded client queues and latest-only telemetry coalescing; disconnect/resync slow clients; metrics on queue saturation.
- **History/UI:** separate durable operational-state events from 30-second `TELEMETRY_SAMPLED` events; clarify replay labels and fidelity assertions.
- **Validation:** concurrency, crash-recovery, SSE overload, and replay-fidelity tests described above.
- **Observability:** breaker state, single-flight contention, generation discards, command outcome distribution (including `UNCERTAIN`), and SSE queue metrics.

## Boundaries retained from the original design

- One modular Spring Boot Supervisor and one simulated-device application.
- MySQL remains the sole durable store for Supervisor event and command records.
- The board writer remains the single authority for state changes; asynchronous I/O results enter it as ordered messages, subject to freshness validation.
- SNMP polling remains authoritative; traps are refresh hints, not directly trusted state.
- `UNKNOWN` is never rendered as `OPERATIONAL`; live and replay are clearly separated.
- No distributed message broker, Redis, Kubernetes requirement or extra microservices for this 15-device demo.
