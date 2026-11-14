import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { AlarmDto, AlarmState, Severity } from '../../../core/api/models';
import { BoardSource } from '../../../core/state/board-source';
import { LiveBoardStore } from '../../../core/state/live-board-store';
import { formatUtc } from '../../../shared/format';

const SEVERITY: Record<Severity, { tag: string; rank: number }> = {
  CRITICAL: { tag: 'CRIT', rank: 0 }, MAJOR: { tag: 'MAJ', rank: 1 }, MINOR: { tag: 'MIN', rank: 2 },
  WARNING: { tag: 'WARN', rank: 3 }, INDETERMINATE: { tag: 'IND', rank: 4 }, CLEARED: { tag: 'CLR', rank: 5 },
};
const STATE: Record<AlarmState, string> = {
  ACTIVE_UNACK: 'UNACK', ACTIVE_ACK: 'ACK', CLEARED_UNACK: 'CLEARED-UNACK', CLOSED: 'CLOSED',
};
const isUnacked = (a: AlarmDto) => a.state === 'ACTIVE_UNACK' || a.state === 'CLEARED_UNACK';

@Component({
  selector: 'app-alarm-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bar">
      <h2>ALARMS  {{ unackedCount() }} unacknowledged</h2>
      <label class="toggle">
        <input type="checkbox" class="suppressed-toggle" [checked]="showSuppressed()"
          (change)="showSuppressed.set(!showSuppressed())" />
        Show suppressed ({{ suppressedCount() }})
      </label>
      <button type="button" [disabled]="selectedIds().length === 0" (click)="ackSelected()">Ack selected</button>
      <button type="button" [disabled]="unackedIds().length === 0" (click)="ackAllVisible()">Ack all visible</button>
      @if (failed()) {
        <span class="error" role="alert">Acknowledge failed</span>
      }
    </div>
    <div class="scroll">
      <table>
        <thead>
          <tr><th></th><th>Sev</th><th>Time</th><th>Equipment</th><th>Alarm</th><th>State</th></tr>
        </thead>
        <tbody>
          @for (a of rows(); track a.id) {
            <tr [class]="'sev-' + a.severity.toLowerCase()">
              <td>
                <input type="checkbox" class="select" [attr.aria-label]="'Select alarm ' + a.id"
                  [checked]="selected().has(a.id)" (change)="toggle(a.id)" />
              </td>
              <td class="sev">{{ sev(a).tag }}</td>
              <td class="mono">{{ time(a.raisedAt) }}</td>
              <td class="mono">{{ a.equipmentId }}</td>
              <td>
                {{ a.text }}@if (a.instanceName) { · {{ a.instanceName }}}
                @if (unconfirmed(a)) {
                  <span class="unconfirmed" title="unconfirmed" aria-label="unconfirmed">?</span>
                }
              </td>
              <td class="mono state">{{ state(a) }}</td>
            </tr>
          } @empty {
            <tr><td colspan="6" class="empty">No alarms</td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    :host { display: block; background: var(--panel); border-top: 1px solid var(--panel-2); }
    .bar { display: flex; align-items: center; gap: 16px; padding: 6px 16px; }
    h2 { margin: 0; flex: 1; font: 600 13px var(--font-mono); letter-spacing: 0.06em; white-space: pre; }
    .toggle { font-size: 12px; color: var(--muted); }
    button { padding: 3px 10px; color: var(--text); background: var(--panel-2); border: 1px solid var(--muted);
      border-radius: 3px; font: 12px var(--font-ui); cursor: pointer; }
    button:disabled { opacity: 0.4; cursor: default; }
    button:focus-visible, input:focus-visible { outline: 2px solid var(--focus); outline-offset: 1px; }
    .error { color: var(--failed); font-size: 12px; }
    .scroll { max-height: 220px; overflow-y: auto; }
    table { width: 100%; border-collapse: collapse; font-size: 12px; }
    th { position: sticky; top: 0; background: var(--panel); text-align: left; color: var(--muted); font-weight: 600; }
    th, td { padding: 3px 8px; border-bottom: 1px solid var(--panel-2); }
    .mono { font-family: var(--font-mono); }
    .sev { font: 700 12px var(--font-mono); }
    .sev-critical .sev { color: var(--failed); }
    .sev-major .sev { color: var(--degraded); }
    .sev-minor .sev, .sev-warning .sev { color: var(--text); }
    .unconfirmed { margin-left: 6px; padding: 0 5px; border: 1px solid var(--unknown); border-radius: 3px;
      color: var(--unknown); font: 700 11px var(--font-mono); }
    .empty { color: var(--muted); text-align: center; }
  `,
})
export class AlarmList {
  private readonly source = inject(BoardSource);
  private readonly store = inject(LiveBoardStore);

  protected readonly showSuppressed = signal(false);
  protected readonly failed = signal(false);

  protected readonly time = formatUtc;
  protected readonly sev = (a: AlarmDto) => SEVERITY[a.severity];
  protected readonly state = (a: AlarmDto) => STATE[a.state];

  private readonly all = computed(() => this.source.board()?.alarms ?? []);
  /**
   * The equipment that is UNKNOWN on the current board. An alarm of such equipment is unconfirmed (spec §5.1); this
   * follows every equipment event, while the DTO's `unconfirmed` flag is only as fresh as the alarm's own last event.
   * Service alarms match no equipment and are never unconfirmed.
   */
  private readonly unknownEquipment = computed(() => new Set(
    (this.source.board()?.equipment ?? []).filter(e => e.health === 'UNKNOWN').map(e => e.id)));
  protected readonly unconfirmed = (a: AlarmDto) => this.unknownEquipment().has(a.equipmentId);
  protected readonly suppressedCount = computed(() => this.all().filter(a => a.suppressed).length);
  protected readonly rows = computed(() =>
    this.all()
      .filter(a => this.showSuppressed() || !a.suppressed)
      .sort((a, b) => SEVERITY[a.severity].rank - SEVERITY[b.severity].rank
        || Date.parse(b.raisedAt) - Date.parse(a.raisedAt)));
  /** The checked rows. Pruned whenever the rows change, so it never holds an alarm that is gone or hidden. */
  protected readonly selected = linkedSignal<AlarmDto[], ReadonlySet<string>>({
    source: this.rows,
    computation: (rows, previous) => {
      const kept = previous?.value ?? new Set<string>();
      const visible = new Set(rows.map(a => a.id));
      return [...kept].every(id => visible.has(id)) ? kept : new Set([...kept].filter(id => visible.has(id)));
    },
  });
  /** The selected alarms among the visible rows, in row order: what "Ack selected" sends. */
  protected readonly selectedIds = computed(() => this.rows().filter(a => this.selected().has(a.id)).map(a => a.id));
  protected readonly unackedIds = computed(() => this.rows().filter(isUnacked).map(a => a.id));
  protected readonly unackedCount = computed(() => this.unackedIds().length);

  protected toggle(id: string): void {
    this.selected.update(set => {
      const next = new Set(set);
      if (!next.delete(id)) {
        next.add(id);
      }
      return next;
    });
  }

  protected ackSelected(): void {
    this.ack(this.selectedIds());
  }

  protected ackAllVisible(): void {
    this.ack(this.unackedIds());
  }

  private ack(ids: string[]): void {
    if (ids.length === 0) {
      return;
    }
    this.failed.set(false);
    this.store.ackSelected(ids).subscribe({
      next: () => this.selected.set(new Set()),
      error: () => this.failed.set(true),
    });
  }
}
