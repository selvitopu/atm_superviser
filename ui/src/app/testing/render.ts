import { EnvironmentProviders, Provider, Signal, Type, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { AckResultDto, AuthConfigDto, BoardDto, UserDto } from '../core/api/models';
import { AuthService, RoleName, roleAllows } from '../core/auth/auth.service';
import { BoardSource } from '../core/state/board-source';
import { Clock } from '../core/state/clock';
import { Connection, LiveBoardStore } from '../core/state/live-board-store';

export class FakeStore {
  readonly board = signal<BoardDto | null>(null);
  readonly connection = signal<Connection>('LIVE');
  readonly dataNotLive = signal(false);
  started = 0;
  stopped = 0;
  acked: string[][] = [];
  start(): void { this.started++; }
  stop(): void { this.stopped++; }
  ackSelected(ids: string[]): Observable<AckResultDto> {
    this.acked.push(ids);
    return of({ acknowledged: [], notFound: [] });
  }
}

export class FakeClock {
  readonly now: Signal<number>;
  constructor(private readonly serverMs: number) {
    this.now = signal(serverMs);
  }
  serverNow(): number { return this.serverMs; }
}

export function storeProviders(store: FakeStore): Provider[] {
  return [
    { provide: LiveBoardStore, useValue: store },
    { provide: BoardSource, useExisting: LiveBoardStore },
  ];
}

export function clockProvider(serverIso: string): Provider {
  return { provide: Clock, useValue: new FakeClock(Date.parse(serverIso)) };
}

export function render<T>(type: Type<T>, inputs: Record<string, unknown> = {}, providers: (Provider | EnvironmentProviders)[] = []) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers });
  const fixture = TestBed.createComponent(type);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  const el = fixture.nativeElement as HTMLElement;
  return {
    fixture,
    el,
    text: () => (el.textContent ?? '').replace(/\s+/g, ' ').trim(),
    all: (selector: string) => Array.from(el.querySelectorAll<HTMLElement>(selector)),
  };
}

/** A signed-in (or, with no roles, signed-out) user for components that read AuthService. */
export class FakeAuth {
  readonly user = signal<UserDto | null>(null);
  readonly config = signal<AuthConfigDto | null>(null);
  signedOut = 0;
  constructor(roles: string[] = ['SUPERVISOR'], displayName = 'Supervisor One', username = 'supervisor1') {
    this.user.set(roles.length ? { username, displayName, roles } : null);
  }
  hasRole(role: RoleName): boolean { return roleAllows(this.user()?.roles ?? [], role); }
  loadConfig(): Observable<AuthConfigDto> {
    const config = this.config() ?? { demoMode: false, registrationEnabled: true, demoAccounts: [] };
    this.config.set(config);
    return of(config);
  }
  me(): Observable<UserDto> {
    const u = this.user();
    return u ? of(u) : throwError(() => new HttpErrorResponse({ status: 401, statusText: 'Unauthorized' }));
  }
  signOut(): Observable<void> { this.signedOut++; this.user.set(null); return of(undefined); }
  sessionEnded(): void { this.user.set(null); }
}

export function authProvider(auth: FakeAuth): Provider {
  return { provide: AuthService, useValue: auth };
}
