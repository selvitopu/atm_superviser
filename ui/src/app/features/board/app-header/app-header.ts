import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
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
    </header>
  `,
  styles: `
    header { display: flex; align-items: center; gap: 24px; padding: 8px 16px; background: var(--panel);
      border-bottom: 1px solid var(--panel-2); }
    h1 { margin: 0; flex: 1; font-size: 14px; font-weight: 600; letter-spacing: 0.08em; }
    .connection, .clock { font: 600 13px var(--font-mono); }
    .ok { color: var(--ok); }
    .degraded { color: var(--degraded); }
    .failed { color: var(--failed); }
  `,
})
export class AppHeader {
  private readonly store = inject(LiveBoardStore);
  private readonly time = inject(Clock);

  protected readonly connection = computed(() => CONNECTION[this.store.connection()]);
  protected readonly clock = computed(() => formatUtc(this.time.serverNow()));
}
