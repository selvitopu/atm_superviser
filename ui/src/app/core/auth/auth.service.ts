import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, finalize, of, switchMap, tap } from 'rxjs';
import { AuthConfigDto, RegisterRequest, UserDto } from '../api/models';

export type RoleName = 'VIEWER' | 'SUPERVISOR' | 'ADMIN';

const RANK: Record<RoleName, number> = { VIEWER: 1, SUPERVISOR: 2, ADMIN: 3 };

/** The role hierarchy, as on the server: ADMIN includes SUPERVISOR, which includes VIEWER. */
export function roleAllows(roles: readonly string[], role: RoleName): boolean {
  return roles.some(r => (RANK[r as RoleName] ?? 0) >= RANK[role]);
}

/** The highest role of a user, for display. */
export function topRole(roles: readonly string[]): string {
  return [...roles].sort((a, b) => (RANK[b as RoleName] ?? 0) - (RANK[a as RoleName] ?? 0))[0] ?? '';
}

/**
 * The signed-in user and the sign-in configuration. Sign-in posts the form to the server's session endpoint; the
 * configuration is loaded first, because that response carries the CSRF cookie the POST must send back.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly userSignal = signal<UserDto | null>(null);
  private readonly configSignal = signal<AuthConfigDto | null>(null);

  readonly user = this.userSignal.asReadonly();
  readonly config = this.configSignal.asReadonly();

  loadConfig(): Observable<AuthConfigDto> {
    return this.http.get<AuthConfigDto>('/api/v1/auth/config').pipe(tap(c => this.configSignal.set(c)));
  }

  me(): Observable<UserDto> {
    return this.http.get<UserDto>('/api/v1/auth/me').pipe(tap(u => this.userSignal.set(u)));
  }

  signIn(username: string, password: string): Observable<UserDto> {
    const config = this.configSignal();
    const ready = config ? of(config) : this.loadConfig();
    // URLSearchParams encodes "+" and "&"; HttpParams would send "+" as is, which the server reads as a space
    const body = new URLSearchParams({ username, password }).toString();
    return ready.pipe(
      switchMap(() => this.http.post<UserDto>('/api/v1/auth/login', body,
        { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } })),
      tap(u => this.userSignal.set(u)),
    );
  }

  signOut(): Observable<void> {
    return this.http.post<void>('/api/v1/auth/logout', null).pipe(finalize(() => this.userSignal.set(null)));
  }

  register(request: RegisterRequest): Observable<UserDto> {
    return this.http.post<UserDto>('/api/v1/auth/register', request);
  }

  hasRole(role: RoleName): boolean {
    return roleAllows(this.userSignal()?.roles ?? [], role);
  }

  /** The server no longer knows this session (a 401 from the API). */
  sessionEnded(): void {
    this.userSignal.set(null);
  }
}
