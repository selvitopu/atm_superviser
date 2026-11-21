# ADR-0010: Session Sign-In with CSRF Protection and a Three-Role Hierarchy

- **Status:** Accepted (implemented)
- **Date:** 2026-11-15
- **Deciders:** Project maintainers

## Context and Problem Statement

Until now anyone who could reach the board could acknowledge alarms, and every acknowledgement was recorded as the
placeholder `demo-user`. A supervision tool must know who accepted an alarm, and only people entitled to it may do so.
The UI is a same-origin Angular single-page application that follows the board over Server-Sent Events.

## Decision Drivers

- **The live stream must stay authenticated:** the browser's `EventSource` cannot send an `Authorization` header.
- **Sign-out and session end must take effect at once,** for every request and for a live stream that is already
  open.
- **No credentials in URLs, logs or browser storage.**
- **Small and explainable:** three fixed roles, accounts in the database, no external identity provider.

## Considered Options

1. **Server-side session cookie, with CSRF protection through a readable `XSRF-TOKEN` cookie** *(Chosen)*
2. **JWT bearer tokens:** the stream would need the token in the URL, and revoking a token before it expires needs
   extra machinery.
3. **HTTP Basic:** browser prompts, credentials on every request, and no real sign-out.

## Decision Outcome

Chosen option: **Option 1.**

- [`SecurityConfig`] uses Spring Security 7:
  - Form sign-in at `POST /api/v1/auth/login` answers JSON. `POST /api/v1/auth/logout` answers 204.
  - Every refusal is an RFC 9457 problem detail: 401 "Sign in to continue", 403 "Your role does not allow this", and
    403 with its own detail for a missing or stale CSRF token. A fault behind sign-in, such as the database being
    down, answers 503 rather than "Wrong username or password". The JSON API answers with no redirects.
  - CSRF uses `csrf.spa()`. [`CsrfCookieFilter`] loads the token on every request, so the cookie is written whenever
    the browser has none yet: a signed-out browser has it before its first POST, which is the sign-in itself. Sign-in
    replaces the token, and the sign-in response carries the new cookie.
  - The session cookie is HttpOnly and SameSite=Lax, and sessions last 12 hours because a supervision console stays
    open for a whole shift.
- **Roles:** `ADMIN > SUPERVISOR > VIEWER`. Every `/api/**` call, the live stream included, needs a signed-in user,
  and acknowledging alarms needs SUPERVISOR (`@PreAuthorize`). The acknowledgement records the signed-in username.
- **Accounts** live in `app_user` and `user_role` ([ADR-0009](0009-postgresql-and-liquibase.md)). Usernames are
  compared trimmed and lower-case. Self-registration creates read-only VIEWER accounts only, and
  `atm.security.registration-enabled` can switch it off.
- **Modules:** the `security` module depends on no other module. The `api` module reads the signed-in name from
  Spring's `Principal`, so it does not depend on `security` either.

### Consequences

- **Good:** the stream, the REST calls and sign-out all work through one cookie, with no token handling in the UI.
- **Good:** a session that ends (expiry, restart, sign-out, an administrator's change) sends the UI back to the
  sign-in screen. The supervisor tracks its live sessions ([`LiveSessions`]) and closes a session's open live streams
  when the session is destroyed, so a window showing the board follows within seconds and any other window at its
  next request.
- **Trade-off:** a disabled account is refused before the password check, so its refusal is measurably faster. That
  tells an attacker an account exists but is disabled, which is accepted for this tool.
- **Trade-off:** sessions live in the supervisor's memory, so a supervisor restart signs everyone out. This is
  acceptable while there is one supervisor ([ADR-0007](0007-supervisor-ha-as-future-work.md)).
- **Not yet:** sign-in rate limiting and auditing of refused requests are planned. User administration came with
  [ADR-0011](0011-control-and-simulator-through-the-supervisor.md).

## Verification

- [`SecurityRulesTest`]: signed-out 401 problem details, a viewer refused, a supervisor and an admin allowed, the
  real username recorded, and a POST without CSRF refused.
- [`CsrfCookieTest`]: a signed-out response carries the readable `XSRF-TOKEN` cookie.
- [`AccountDetailsServiceIT`] and [`AccountServiceIT`]: case-insensitive sign-in, disabled accounts, and the
  registration rules.
- [`SupervisorEndToEndIT`]: the real server: signed-out board and stream refused, sign-in without the CSRF token
  refused, a viewer cannot acknowledge.

[`SecurityConfig`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/security/SecurityConfig.java
[`CsrfCookieFilter`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/security/CsrfCookieFilter.java
[`SecurityRulesTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/security/SecurityRulesTest.java
[`CsrfCookieTest`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/security/CsrfCookieTest.java
[`AccountDetailsServiceIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/security/AccountDetailsServiceIT.java
[`AccountServiceIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/security/AccountServiceIT.java
[`SupervisorEndToEndIT`]: ../../supervisor/src/test/java/io/github/selvitopu/atmsup/supervisor/SupervisorEndToEndIT.java
[`LiveSessions`]: ../../supervisor/src/main/java/io/github/selvitopu/atmsup/supervisor/security/LiveSessions.java
