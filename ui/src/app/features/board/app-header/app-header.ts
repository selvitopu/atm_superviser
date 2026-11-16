import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService, topRole } from '../../../core/auth/auth.service';
import { Clock } from '../../../core/state/clock';
import { LiveBoardStore } from '../../../core/state/live-board-store';
import { formatUtc } from '../../../shared/format';

const CONNECTION = {
  LIVE: { label: '● LIVE', cls: 'ok' },
  RECONNECTING: { label: '◌ RECONNECTING', cls: 'degraded' },
  OFFLINE: { label: '✕ OFFLINE', cls: 'failed' },
};

@Component({
  selector: 'app-app-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive],
  template: `
    <header>
      <h1>ATM SUPERVISOR · DEMO ACC</h1>
      <nav aria-label="Screens">
        @for (link of links(); track link.path) {
          <a [routerLink]="link.path" routerLinkActive="active" ariaCurrentWhenActive="page">{{ link.label }}</a>
        }
      </nav>
      @if (live()) {
        <span class="connection" [class]="'connection ' + connection().cls">{{ connection().label }}</span>
        <time class="clock">{{ clock() }}</time>
      }
      @if (user(); as u) {
        <span class="user">{{ u.displayName }} · {{ role() }}</span>
        <button type="button" class="sign-out" (click)="signOut()">Sign out</button>
      }
    </header>
  `,
  styles: `
    header { display: flex; align-items: center; gap: 24px; padding: 8px 16px; background: var(--panel);
      border-bottom: 1px solid var(--panel-2); }
    h1 { margin: 0; font-size: 14px; font-weight: 600; letter-spacing: 0.08em; }
    nav { flex: 1; display: flex; gap: 4px; }
    nav a { padding: 3px 10px; color: var(--muted); font: 600 12px var(--font-mono); text-decoration: none;
      border: 1px solid transparent; border-radius: 3px; }
    nav a:hover { color: var(--text); }
    nav a.active { color: var(--text); border-color: var(--panel-2); background: var(--panel-2); }
    nav a:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
    .connection, .clock { font: 600 13px var(--font-mono); }
    .user { font: 600 12px var(--font-mono); color: var(--muted); letter-spacing: 0.04em; }
    .sign-out { padding: 3px 10px; font: 600 12px var(--font-mono); color: var(--text); background: var(--panel-2);
      border: 1px solid var(--muted); border-radius: 3px; cursor: pointer; }
    .sign-out:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
    .ok { color: var(--ok); }
    .degraded { color: var(--degraded); }
    .failed { color: var(--failed); }
  `,
})
export class AppHeader {
  private readonly store = inject(LiveBoardStore);
  private readonly time = inject(Clock);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** The live state and the server clock belong to the board; other screens do not stream. */
  readonly live = input(false);

  protected readonly user = this.auth.user;
  /** The screens this user may open; the server refuses the same actions to anyone else. */
  protected readonly links = computed(() => {
    if (!this.user()) {
      return [];
    }
    const links = [{ path: '/board', label: 'Board' }, { path: '/history', label: 'Alarm history' }];
    if (this.config()?.demoMode && this.auth.hasRole('SUPERVISOR')) {
      links.push({ path: '/simulator', label: 'Simulator' });
    }
    return links;
  });
  private readonly config = this.auth.config;

  constructor() {
    if (!this.auth.config()) {
      this.auth.loadConfig().subscribe({ error: () => {} }); // after a reload: is this the demo centre?
    }
  }
  protected readonly role = computed(() => topRole(this.user()?.roles ?? []));

  protected readonly connection = computed(() => CONNECTION[this.store.connection()]);
  protected readonly clock = computed(() => formatUtc(this.time.serverNow()));

  protected signOut(): void {
    const toSignIn = () => void this.router.navigate(['/signin']);
    this.auth.signOut().subscribe({ complete: toSignIn, error: toSignIn });
  }
}
