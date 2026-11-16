import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AlarmQuery, AlarmSearch } from '../../../core/api/alarm-search';
import { BoardApi } from '../../../core/api/board-api';
import { AlarmDto, PageDto } from '../../../core/api/models';
import { formatUtc } from '../../../shared/format';
import { AppHeader } from '../../board/app-header/app-header';

const SIZE = 50;
const SEVERITIES = ['CRITICAL', 'MAJOR', 'MINOR', 'WARNING'];
const STATES: Record<string, string> = {
  ACTIVE_UNACK: 'UNACK', ACTIVE_ACK: 'ACK', CLEARED_UNACK: 'CLEARED-UNACK', CLOSED: 'CLOSED',
};

/** "2026-11-16T08:00" from a datetime-local input, read as UTC, to an ISO instant; empty stays empty. */
function utc(local: string): string {
  return local ? new Date(local + ':00Z').toISOString() : '';
}

/** Every alarm, open or closed, newest first: who acknowledged it and when it cleared. */
@Component({
  selector: 'app-history-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AppHeader],
  template: `
    <app-app-header />
    <main>
      <h2>ALARM HISTORY</h2>
      <form class="filters" (submit)="$event.preventDefault(); search(0)">
        <label>State
          <select class="state" (change)="state.set($any($event.target).value)">
            <option value="all" [selected]="state() === 'all'">All</option>
            <option value="open" [selected]="state() === 'open'">Open</option>
          </select>
        </label>
        <label>Severity
          <select class="severity" (change)="severity.set($any($event.target).value)">
            <option value="">Any</option>
            @for (s of severities; track s) { <option [value]="s">{{ s }}</option> }
          </select>
        </label>
        <label>Equipment
          <select class="equipment" (change)="equipment.set($any($event.target).value)">
            <option value="">Any</option>
            @for (id of equipmentIds(); track id) { <option [value]="id">{{ id }}</option> }
          </select>
        </label>
        <label>From (UTC) <input type="datetime-local" class="from" (input)="from.set($any($event.target).value)" /></label>
        <label>To (UTC) <input type="datetime-local" class="to" (input)="to.set($any($event.target).value)" /></label>
        <button type="submit">Search</button>
      </form>

      @if (failed()) {
        <p class="error" role="alert">Search failed. Try again.</p>
      }
      <div class="scroll">
        <table>
          <thead>
            <tr><th>Raised</th><th>Sev</th><th>Equipment</th><th>Alarm</th><th>State</th><th>Acknowledged</th>
              <th>Cleared</th></tr>
          </thead>
          <tbody>
            @for (a of rows(); track a.id) {
              <tr [class]="'sev-' + a.severity.toLowerCase()">
                <td class="mono">{{ time(a.raisedAt) }}</td>
                <td class="mono sev">{{ a.severity }}</td>
                <td class="mono">{{ a.equipmentId }}</td>
                <td>{{ a.code }} {{ a.text }}@if (a.instanceName) { · {{ a.instanceName }}}</td>
                <td class="mono">{{ stateLabel(a) }}</td>
                <td class="mono">@if (a.ackedBy) {{{ a.ackedBy }} {{ time(a.ackedAt) }}}</td>
                <td class="mono">{{ time(a.clearedAt) }}</td>
              </tr>
            } @empty {
              @if (result()) { <tr><td colspan="7" class="empty">No alarms match</td></tr> }
            }
          </tbody>
        </table>
      </div>
      <nav class="pager" aria-label="Pages">
        <span class="mono">{{ range() }}</span>
        <button type="button" [disabled]="!hasPrevious()" (click)="search(page() - 1)">Previous</button>
        <button type="button" [disabled]="!hasNext()" (click)="search(page() + 1)">Next</button>
      </nav>
    </main>
  `,
  styles: `
    :host { display: flex; flex-direction: column; height: 100vh; }
    main { flex: 1; display: flex; flex-direction: column; gap: 12px; padding: 16px; min-height: 0; }
    h2 { margin: 0; font: 600 13px var(--font-mono); letter-spacing: 0.08em; }
    .filters { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px; }
    label { display: flex; flex-direction: column; gap: 4px; font: 600 11px var(--font-mono); color: var(--muted);
      letter-spacing: 0.04em; }
    select, input { padding: 4px 6px; color: var(--text); background: var(--panel); border: 1px solid var(--panel-2);
      border-radius: 3px; font: 12px var(--font-mono); color-scheme: dark; }
    button { padding: 4px 12px; color: var(--text); background: var(--panel-2); border: 1px solid var(--muted);
      border-radius: 3px; font: 600 12px var(--font-mono); cursor: pointer; }
    button:disabled { opacity: 0.4; cursor: default; }
    button:focus-visible, select:focus-visible, input:focus-visible { outline: 2px solid var(--focus); outline-offset: 1px; }
    .scroll { flex: 1; overflow: auto; background: var(--panel); border: 1px solid var(--panel-2); }
    table { width: 100%; border-collapse: collapse; font-size: 12px; }
    th { position: sticky; top: 0; background: var(--panel); text-align: left; color: var(--muted); font-weight: 600; }
    th, td { padding: 4px 8px; border-bottom: 1px solid var(--panel-2); }
    .mono { font-family: var(--font-mono); }
    .sev { font-weight: 700; }
    .sev-critical .sev { color: var(--failed); }
    .sev-major .sev { color: var(--degraded); }
    .empty { color: var(--muted); text-align: center; }
    .error { margin: 0; color: var(--failed); font-size: 12px; }
    .pager { display: flex; align-items: center; justify-content: flex-end; gap: 12px; font-size: 12px; }
  `,
})
export class HistoryPage {
  private readonly alarms = inject(AlarmSearch);
  protected readonly severities = SEVERITIES;
  /** Date and time: the history spans days. */
  protected readonly time = (iso: string | null | undefined) => iso ? iso.slice(0, 10) + ' ' + formatUtc(iso) : '';
  protected readonly stateLabel = (a: AlarmDto) => STATES[a.state] ?? a.state;

  protected readonly state = signal<'all' | 'open'>('all');
  protected readonly severity = signal('');
  protected readonly equipment = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly equipmentIds = signal<string[]>([]);

  protected readonly result = signal<PageDto<AlarmDto> | null>(null);
  protected readonly failed = signal(false);
  protected readonly page = computed(() => this.result()?.page ?? 0);
  protected readonly rows = computed(() => this.result()?.items ?? []);
  protected readonly hasPrevious = computed(() => this.page() > 0);
  protected readonly hasNext = computed(() => {
    const r = this.result();
    return !!r && (r.page + 1) * r.size < r.total;
  });
  protected readonly range = computed(() => {
    const r = this.result();
    if (!r || r.total === 0) {
      return '';
    }
    const first = r.page * r.size + 1;
    return `${first}–${first + r.items.length - 1} of ${r.total}`;
  });

  constructor() {
    inject(BoardApi).board().subscribe({ next: b => this.equipmentIds.set(b.equipment.map(e => e.id)), error: () => {} });
    this.search(0);
  }

  protected search(page: number): void {
    const query: AlarmQuery = {
      state: this.state(), severity: this.severity(), equipment: this.equipment(),
      from: utc(this.from()), to: utc(this.to()), page, size: SIZE,
    };
    this.failed.set(false);
    this.alarms.search(query).subscribe({
      next: r => this.result.set(r),
      error: () => this.failed.set(true),
    });
  }
}
