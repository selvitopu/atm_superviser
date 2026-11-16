import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, switchMap } from 'rxjs';
import { BoardApi } from '../../../core/api/board-api';
import { EquipmentDetailDto, EquipmentDto } from '../../../core/api/models';
import { BoardSource } from '../../../core/state/board-source';
import { Clock } from '../../../core/state/clock';
import { freshness } from '../../../core/state/freshness';
import { display, formatUtc } from '../../../shared/format';
import { unknownReasonLabel } from '../../../shared/unknown-reason';
import { EquipmentControl } from '../equipment-control/equipment-control';
import { StatusBadge } from '../status-badge/status-badge';

const FIELDS: Record<string, [string, string][]> = {
  RADAR: [
    ['Rotation (ms)', 'rotationPeriodMs'], ['Plots/scan', 'plotsPerScan'], ['Active channel', 'activeChannel'],
    ['Channel A', 'channelAStatus'], ['Channel B', 'channelBStatus'],
  ],
  SDPS: [['Tracks', 'trackCount'], ['Latency (ms)', 'latencyMs']],
  FDPS: [['Flight plans', 'flightPlanCount'], ['Queue depth', 'queueDepth'], ['Latency (ms)', 'latencyMs']],
};

@Component({
  selector: 'app-detail-panel',
  imports: [StatusBadge, EquipmentControl],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (equipment(); as e) {
      <h2>{{ e.name }} <span class="id">{{ e.id }}</span></h2>
      <p class="line">
        <app-status-badge [status]="e.health" />
        <span class="mono">since {{ since() }}</span>
        @if (reasonLabel(); as reason) {
          <span class="reason">{{ reason }}</span>
        }
      </p>
      <dl>
        <dt>Role</dt><dd>{{ display(e.role) }}</dd>
        <dt>Maintenance</dt><dd>{{ e.maintenance ? 'MAINT' : 'no' }}</dd>
      </dl>
      <app-equipment-control [equipment]="e" />
      <section class="values">
        @if (unknown()) {
          <h3>Last known values</h3>
        }
        <div [class.dim]="unknown()">
          <dl>
            @for (f of fields(); track f.label) {
              <dt>{{ f.label }}</dt><dd>{{ f.value }}</dd>
            }
          </dl>
          @if (sensors().length > 0) {
            <table class="sensors">
              <thead><tr><th>Sensor</th><th>Input</th></tr></thead>
              <tbody>
                @for (s of sensors(); track s['index'] ?? $index) {
                  <tr><td>{{ display(s['name']) }}</td><td>{{ display(s['inputStatus']) }}</td></tr>
                }
              </tbody>
            </table>
          }
        </div>
      </section>
      <p class="poll mono">Last poll {{ lastPoll() }}</p>
      <h3>Recent events</h3>
      <ul class="events">
        @if (detail(); as d) {
          @for (ev of d.recentEvents; track ev.id) {
            <li><span class="mono">{{ time(ev.occurredAt) }}</span> {{ ev.type }} · {{ ev.summary }}</li>
          } @empty {
            <li class="muted">none</li>
          }
        } @else if (loadFailed()) {
          <li class="muted unavailable">Events unavailable</li>
        } @else {
          <li class="muted">Loading…</li>
        }
      </ul>
    } @else {
      <p class="muted">{{ equipmentId() ? 'Loading ' + equipmentId() + '…' : 'Select equipment' }}</p>
    }
  `,
  styles: `
    :host { display: block; padding: 12px 16px; font-size: 13px; }
    h2 { margin: 0 0 8px; font-size: 15px; }
    h3 { margin: 16px 0 6px; font: 600 12px var(--font-ui); letter-spacing: 0.1em; color: var(--muted); }
    .id, .mono { font-family: var(--font-mono); }
    .id { color: var(--muted); font-size: 12px; }
    .line { display: flex; gap: 10px; align-items: center; margin: 0 0 8px; }
    .reason { font: 12px var(--font-mono); color: var(--muted); }
    dl { display: grid; grid-template-columns: 120px 1fr; gap: 4px 12px; margin: 0; }
    .values { margin-top: 4px; }
    .values h3 { margin: 10px 0 4px; }
    .dim { opacity: 0.4; }
    dt { color: var(--muted); }
    dd { margin: 0; font-family: var(--font-mono); }
    table { width: 100%; margin-top: 10px; border-collapse: collapse; font-family: var(--font-mono); font-size: 12px; }
    th { text-align: left; color: var(--muted); font-weight: 600; }
    th, td { padding: 2px 4px; border-bottom: 1px solid var(--panel-2); }
    .events { margin: 0; padding: 0; list-style: none; font-size: 12px; }
    .events li { padding: 2px 0; }
    .poll { margin: 8px 0 0; font-size: 12px; color: var(--muted); }
    .muted { color: var(--muted); }
  `,
})
export class DetailPanel {
  readonly equipmentId = input<string | null>(null);

  private readonly source = inject(BoardSource);
  private readonly api = inject(BoardApi);
  private readonly clock = inject(Clock);

  protected readonly display = display;
  protected readonly time = formatUtc;

  private readonly live = computed(() => this.source.board()?.equipment.find(e => e.id === this.equipmentId()));

  /** Primitive key: telemetry changes leave it equal, so only a real change refetches the detail. */
  private readonly refetchKey = computed(() => {
    const id = this.equipmentId();
    if (!id) {
      return null;
    }
    const e = this.live();
    const alarms = this.source.board()?.alarms.filter(a => a.equipmentId === id).length ?? 0;
    return `${id}|${e?.health}|${e?.maintenance}|${e?.role}|${alarms}`;
  });

  /** Latest fetch result tagged with the id it was for, so a stale result is never shown for another unit. */
  private readonly fetched = toSignal(
    toObservable(this.refetchKey).pipe(
      switchMap(key => {
        if (key === null) {
          return of(null);
        }
        const id = this.equipmentId()!;
        return this.api.equipment(id).pipe(
          map(detail => ({ id, detail })),
          catchError(() => of({ id, detail: null })),
        );
      }),
    ),
    { initialValue: null as { id: string; detail: EquipmentDetailDto | null } | null },
  );

  protected readonly detail = computed(() => {
    const f = this.fetched();
    return f && f.id === this.equipmentId() ? f.detail : null;
  });
  protected readonly loadFailed = computed(() => {
    const f = this.fetched();
    return !!f && f.id === this.equipmentId() && f.detail === null;
  });

  protected readonly equipment = computed<EquipmentDto | null>(() => this.live() ?? this.detail()?.equipment ?? null);
  protected readonly since = computed(() => formatUtc(this.equipment()?.healthSince));
  /** While health is UNKNOWN the values are the last known ones: dimmed, labelled, never shown as current. */
  protected readonly unknown = computed(() => this.equipment()?.health === 'UNKNOWN');
  protected readonly reasonLabel = computed(() => {
    const e = this.equipment();
    return e ? unknownReasonLabel(e) : null;
  });
  protected readonly lastPoll = computed(() => {
    const c = this.equipment()?.contact;
    const f = c ? freshness(c.lastPollAt, c.pollIntervalMs, this.clock.serverNow()) : null;
    return f?.ageMs == null ? '—' : `${Math.round(f.ageMs / 1000)} s ago`;
  });
  protected readonly fields = computed(() => {
    const e = this.equipment();
    return e ? (FIELDS[e.type] ?? []).map(([label, key]) => ({ label, value: display(e.values[key]) })) : [];
  });
  protected readonly sensors = computed(() => this.equipment()?.tables['sensors'] ?? []);
}
