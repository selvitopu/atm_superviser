import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
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
  template: `
    <header>
      <h1>ATM SUPERVISOR · DEMO ACC</h1>
      <span class="connection" [class]="'connection ' + connection().cls">{{ connection().label }}</span>
      <time class="clock">{{ clock() }}</time>
      @if (user(); as u) {
        <span class="user">{{ u.displayName }} · {{ role() }}</span>
        <button type="button" class="sign-out" (click)="signOut()">Sign out</button>
      }
    </header>
  `,
  styles: `
    header { display: flex; align-items: center; gap: 24px; padding: 8px 16px; background: var(--panel);
      border-bottom: 1px solid var(--panel-2); }
    h1 { margin: 0; flex: 1; font-size: 14px; font-weight: 600; letter-spacing: 0.08em; }
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

  protected readonly user = this.auth.user;
  protected readonly role = computed(() => topRole(this.user()?.roles ?? []));

  protected readonly connection = computed(() => CONNECTION[this.store.connection()]);
  protected readonly clock = computed(() => formatUtc(this.time.serverNow()));

  protected signOut(): void {
    const toSignIn = () => void this.router.navigate(['/signin']);
    this.auth.signOut().subscribe({ complete: toSignIn, error: toSignIn });
  }
}
