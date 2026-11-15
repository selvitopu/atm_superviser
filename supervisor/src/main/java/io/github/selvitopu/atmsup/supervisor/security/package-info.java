/**
 * Accounts, sign-in and roles: the users created by the Liquibase changelog, session-based sign-in with CSRF
 * protection, the role hierarchy ADMIN > SUPERVISOR > VIEWER, and the guard against demo accounts outside demo mode.
 */
@ApplicationModule(allowedDependencies = {})
package io.github.selvitopu.atmsup.supervisor.security;

import org.springframework.modulith.ApplicationModule;
