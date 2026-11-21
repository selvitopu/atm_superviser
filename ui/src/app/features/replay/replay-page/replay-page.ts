import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ReplayApi } from '../../../core/api/replay-api';
import { TimelineDto } from '../../../core/api/models';
import { BoardSource } from '../../../core/state/board-source';
import { formatUtc } from '../../../shared/format';
import { AlarmList } from '../../alarms/alarm-list/alarm-list';
import { DetailPanel } from '../../board/detail-panel/detail-panel';
import { EquipmentBoard } from '../../board/equipment-board/equipment-board';
import { ServiceStrip } from '../../board/service-strip/service-strip';
import { ReplayBand } from '../replay-band/replay-band';
import { ReplaySpeed, ReplayStore } from '../replay-store';
import { ReplayTimeline } from '../replay-timeline/replay-timeline';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const MAX_WINDOW = 7 * 24 * HOUR;
const SPEEDS: ReplaySpeed[] = [1, 4, 16, 60];

function instant(value: string | null): number | null {
  const ms = value ? Date.parse(value) : NaN;
  return Number.isNaN(ms) ? null : ms;
}

/**
 * The board as it was at a past moment. It owns its {@link ReplayStore} and provides it as the {@link BoardSource}, so
 * the live store is never read here and the replay store dies with the page. No control, no acknowledgement and no link
 * to a control screen: the only way out is Back to live.
 */
@Component({
  selector: 'app-replay-page',
  imports: [ReplayBand, ServiceStrip, EquipmentBoard, DetailPanel, AlarmList, ReplayTimeline],
  providers: [ReplayStore, { provide: BoardSource, useExisting: ReplayStore }],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-replay-band [at]="store.at()" [from]="store.window().from" [to]="store.window().to" />
    <app-service-strip class="services" />
    <main class="board">
      <app-equipment-board [replay]="true" />
      @if (store.noData()) {
        <div class="gap" role="status">No recorded data: supervisor not running</div>
      }
    </main>
    <aside class="detail"><app-detail-panel [readOnly]="true" /></aside>
    <section class="controls" aria-label="Replay controls">
      <app-replay-timeline [from]="store.window().from" [to]="store.window().to" [at]="store.at()"
        [data]="timeline()" (seek)="store.seek($event)" />
      <div class="buttons">
        <button type="button" (click)="store.previous()">Previous event</button>
        <button type="button" class="play" (click)="store.playing() ? store.pause() : store.play()">
          {{ store.playing() ? 'Pause' : 'Play' }}</button>
        <button type="button" (click)="store.next()">Next event</button>
        <span class="speeds" role="group" aria-label="Speed">
          @for (s of speeds; track s) {
            <button type="button" [attr.aria-pressed]="store.speed() === s" [class.on]="store.speed() === s"
              (click)="store.setSpeed(s)">{{ s }}×</button>
          }
        </span>
        <details class="export">
          <summary>Export incident report</summary>
          <a [href]="exportUrl('csv')">CSV</a>
          <a [href]="exportUrl('json')">JSON</a>
        </details>
      </div>
      @if (notice(); as text) { <p class="notice" role="alert">{{ text }}</p> }
      @if (store.error()) {
        <p class="notice" role="alert">The recorded history could not be loaded. Seek, or press Play, to try again.</p>
      }
      <ol class="ticker" aria-label="Recent events">
        @for (t of store.ticker(); track $index) {
          <li><span class="mono">{{ time(t.at) }}</span> · {{ t.summary }}</li>
        }
      </ol>
    </section>
    <app-alarm-list class="alarms" [readOnly]="true" />
  `,
  styles: `
    :host { display: grid; min-width: 1280px; height: 100vh; grid-template-columns: 1fr 360px;
      grid-template-rows: auto auto 1fr auto auto;
      grid-template-areas: 'band band' 'services services' 'board detail' 'controls controls' 'alarms alarms'; }
    app-replay-band { grid-area: band; }
    .services { grid-area: services; }
    .board { grid-area: board; position: relative; padding: 8px 16px; overflow: auto; }
    .gap { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
      background: rgb(11 15 20 / 0.85); color: var(--degraded); font: 700 16px var(--font-mono);
      border: 2px dashed var(--degraded); pointer-events: none; }
    .detail { grid-area: detail; overflow: auto; background: var(--panel); border-left: 1px solid var(--panel-2); }
    .controls { grid-area: controls; background: var(--panel); border-top: 1px solid var(--panel-2); }
    .alarms { grid-area: alarms; }
    .buttons { display: flex; align-items: center; gap: 8px; padding: 4px 16px; }
    .speeds { display: inline-flex; gap: 4px; margin-left: 12px; }
    button, summary { padding: 3px 10px; color: var(--text); background: var(--panel-2); border: 1px solid var(--muted);
      border-radius: 3px; font: 600 12px var(--font-mono); cursor: pointer; }
    button.on { border-color: var(--focus); text-decoration: underline; }
    button:focus-visible, summary:focus-visible, a:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
    .export { margin-left: auto; display: flex; gap: 8px; align-items: center; }
    .export a { color: var(--focus); font: 600 12px var(--font-mono); margin-left: 8px; }
    .notice { margin: 0; padding: 2px 16px; color: var(--degraded); font: 12px var(--font-mono); }
    .ticker { margin: 0; padding: 2px 16px; list-style: none; max-height: 72px; overflow-y: auto; font: 12px var(--font-mono);
      color: var(--muted); }
  `,
})
export class ReplayPage {
  protected readonly store = inject(ReplayStore);
  private readonly api = inject(ReplayApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly speeds = SPEEDS;
  protected readonly time = formatUtc;
  protected readonly timeline = signal<TimelineDto | null>(null);
  protected readonly notice = signal<string | null>(null);
  private readonly opened = signal(false);

  protected readonly fromIso = computed(() => new Date(this.store.window().from).toISOString());
  protected readonly toIso = computed(() => new Date(this.store.window().to).toISOString());

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    const alarm = params.get('alarm');
    if (alarm) {
      const sub = this.api.raised(alarm).subscribe({
        next: r => {
          const at = Date.parse(r.occurredAt);
          this.open(at - 15 * MINUTE, at + 45 * MINUTE, at - MINUTE);
        },
        error: () => {
          this.notice.set('No recorded raise was found for that alarm; showing the last hour.');
          this.open(null, null, null);
        },
      });
      inject(DestroyRef).onDestroy(() => sub.unsubscribe());
    } else {
      this.open(instant(params.get('from')), instant(params.get('to')), instant(params.get('at')));
    }
    // The URL follows the replayed instant while paused, so it can be shared.
    effect(() => {
      const at = this.store.at();
      if (this.opened() && !this.store.playing()) {
        untracked(() => this.writeUrl(at));
      }
    });
  }

  /** Defaults: {@code to} is now, {@code from} an hour earlier, {@code at} the start; a window is at most 7 days. */
  private open(from: number | null, to: number | null, at: number | null): void {
    const end = to ?? Date.now();
    let start = from ?? end - HOUR;
    if (start >= end) {
      start = end - HOUR;
    }
    start = Math.max(start, end - MAX_WINDOW);
    this.store.open(start, end, at ?? start);
    this.opened.set(true);
    this.api.timeline(new Date(start).toISOString(), new Date(end).toISOString()).subscribe({
      next: t => this.timeline.set(t),
      error: () => this.notice.update(n => n ?? 'The timeline could not be loaded.'),
    });
  }

  private writeUrl(at: number): void {
    const { from, to } = this.store.window();
    void this.router.navigate([], {
      relativeTo: this.route, replaceUrl: true, queryParamsHandling: 'merge',
      queryParams: { from: new Date(from).toISOString(), to: new Date(to).toISOString(),
        at: new Date(at).toISOString(), alarm: null },
    }).catch(() => {});
  }

  protected exportUrl(format: 'csv' | 'json'): string {
    return `/api/v1/history/export?from=${encodeURIComponent(this.fromIso())}&to=${encodeURIComponent(this.toIso())}`
      + `&format=${format}`;
  }
}
