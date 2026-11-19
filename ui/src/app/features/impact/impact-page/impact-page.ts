import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, distinctUntilChanged, interval, map, of, startWith, switchMap } from 'rxjs';
import { BoardApi } from '../../../core/api/board-api';
import { ImpactApi, VERDICT_LABEL } from '../../../core/api/impact-api';
import { ImpactDto, ServiceChangeDto } from '../../../core/api/models';
import { AppHeader } from '../../board/app-header/app-header';

const REFRESH_MS = 5000;
const SYMBOL: Record<string, string> = { SERVICE_LOSS: '✕ ', REDUCED_MARGIN: '▲ ' };
const VERDICT_CLASS: Record<string, string> = {
  SERVICE_LOSS: 'loss', REDUCED_MARGIN: 'margin', NO_SERVICE_IMPACT: 'none',
};
/** Units in these states are already out, so ticking them would change nothing. */
const ALREADY_OUT = ['FAILED', 'MAINTENANCE'];

interface Unit { id: string; group: string }

/** HH:MM:SS UTC of an instant. */
function utcTime(iso: string | number): string {
  return new Date(iso).toISOString().slice(11, 19) + ' UTC';
}

/**
 * What taking units into maintenance would do to the services, and which single unit failures would hurt now. The
 * plan lives in the URL query so it can be shared; the report refreshes every 5 s and only the latest request lands.
 */
@Component({
  selector: 'app-impact-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AppHeader],
  template: `
    <app-app-header />
    <main>
      <div class="bar">
        <h2>SERVICE IMPACT</h2>
        @if (report(); as r) {
          <span class="mono muted">as of {{ time(r.asOf) }} · refreshes every 5 s</span>
        } @else {
          <span class="mono muted">refreshes every 5 s</span>
        }
      </div>

      <section class="planner" aria-label="Plan maintenance">
        <h3>Plan maintenance</h3>
        @for (g of groups(); track g.name) {
          <fieldset>
            <legend>{{ g.name }}</legend>
            @for (u of g.units; track u.id) {
              <label>
                <input type="checkbox" [value]="u.id" [checked]="isChecked(u.id)" [disabled]="reason(u.id) !== null"
                  (change)="toggle(u.id)" />
                {{ u.id }}@if (reason(u.id); as why) { <span class="muted"> {{ why }}</span> }
              </label>
            }
          </fieldset>
        }
        <button type="button" (click)="clear()">Clear</button>
      </section>

      @if (error(); as message) {
        <p class="error" role="alert">{{ message }}</p>
      }
      @if (failedAt(); as at) {
        <p class="error" role="alert">Last refresh failed at {{ at }}</p>
      }

      @if (report(); as r) {
        <section class="plan" aria-label="Plan result">
          @if (r.plan.units.length) {
            <p class="verdict" [class]="'verdict ' + verdictClass(r.plan.verdict)">{{ verdict(r.plan.verdict) }}
              <span class="muted">— taking {{ r.plan.units.join(', ') }} into maintenance</span></p>
            <ul class="services">
              @for (s of r.plan.services; track s.id) {
                <li class="mono">{{ change(s) }}@if (s.redundancyLostAfter) { · redundancy lost }@for (why of s.reasonsAfter; track why) { · {{ why }}}</li>
              }
            </ul>
            @for (n of r.plan.notes; track n) {
              <p class="note">Note: {{ n }}</p>
            }
          } @else {
            <p>No maintenance planned</p>
          }
          @if (r.plan.unconfirmed) {
            <p class="unconfirmed">Unconfirmed: depends on units in UNKNOWN state: {{ r.unknownUnits.join(', ') }}</p>
          }
        </section>

        <div class="scroll">
          <table>
            <caption>{{ r.plan.units.length ? 'Single points of failure (with the plan applied)' : 'Single points of failure' }}</caption>
            <thead>
              <tr><th>Unit</th><th>State now</th><th>If it fails</th><th>Services</th></tr>
            </thead>
            <tbody>
              @for (p of r.singlePoints; track p.unit) {
                <tr>
                  <td class="mono">{{ p.unit }}</td>
                  <td class="mono">{{ p.health }}</td>
                  <td class="mono"><span [class]="'verdict ' + verdictClass(p.verdict)">{{ verdict(p.verdict) }}</span>@if (p.unconfirmed) { <span class="muted"> unconfirmed</span> }</td>
                  <td class="mono">
                    @for (c of p.changes; track c.id) {
                      <div>{{ change(c) }}@if (c.reasonsAfter.length) {: {{ c.reasonsAfter.join('; ') }}}</div>
                    } @empty { — }
                  </td>
                </tr>
              }
              @for (o of r.outOfService; track o.unit) {
                <tr>
                  <td class="mono">{{ o.unit }}</td>
                  <td class="mono">—</td>
                  <td class="mono">Out of service</td>
                  <td class="mono">{{ o.reason.toLowerCase() }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <footer>{{ r.basis }}</footer>
      }
    </main>
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 100vh; }
    main { flex: 1; display: flex; flex-direction: column; gap: 12px; padding: 16px; }
    .bar { display: flex; align-items: baseline; justify-content: space-between; }
    h2 { margin: 0; font: 600 13px var(--font-mono); letter-spacing: 0.08em; }
    h3 { margin: 0 0 6px; font: 600 11px var(--font-mono); color: var(--muted); letter-spacing: 0.04em; }
    p { margin: 0; }
    .mono { font-family: var(--font-mono); }
    .muted { color: var(--muted); }
    .planner { padding: 8px 12px; background: var(--panel); border: 1px solid var(--panel-2); }
    fieldset { display: inline-flex; flex-wrap: wrap; gap: 4px 12px; margin: 0 12px 6px 0; padding: 4px 8px;
      border: 1px solid var(--panel-2); }
    legend { padding: 0 4px; font: 600 10px var(--font-mono); color: var(--muted); letter-spacing: 0.04em; }
    label { font: 12px var(--font-mono); }
    input { accent-color: var(--muted); color-scheme: dark; }
    button { padding: 4px 12px; color: var(--text); background: var(--panel-2); border: 1px solid var(--muted);
      border-radius: 3px; font: 600 12px var(--font-mono); cursor: pointer; }
    button:focus-visible, input:focus-visible { outline: 2px solid var(--focus); outline-offset: 1px; }
    .plan { padding: 8px 12px; background: var(--panel); border: 1px solid var(--panel-2); display: flex;
      flex-direction: column; gap: 6px; }
    .services { margin: 0; padding: 0; list-style: none; font-size: 12px; }
    .note, .unconfirmed { font-size: 12px; color: var(--muted); }
    .verdict { font: 700 13px var(--font-mono); }
    .loss { color: var(--failed); }
    .margin { color: var(--degraded); }
    .none { color: var(--text); }
    .scroll { overflow: auto; background: var(--panel); border: 1px solid var(--panel-2); }
    table { width: 100%; border-collapse: collapse; font-size: 12px; }
    caption { padding: 6px 8px; text-align: left; color: var(--muted); font: 600 11px var(--font-mono); }
    th { text-align: left; color: var(--muted); font-weight: 600; }
    th, td { padding: 4px 8px; border-bottom: 1px solid var(--panel-2); vertical-align: top; }
    .error { color: var(--failed); font-size: 12px; }
    footer { font-size: 11px; color: var(--muted); }
  `,
})
export class ImpactPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(ImpactApi);

  protected readonly verdict = (v: string) => (SYMBOL[v] ?? '') + (VERDICT_LABEL[v] ?? v);
  protected readonly verdictClass = (v: string) => VERDICT_CLASS[v] ?? 'none';
  protected readonly time = utcTime;
  protected readonly change = (c: ServiceChangeDto) => `${c.id} ${c.before} → ${c.after}`;

  /** The units of the plan, from the URL. */
  protected readonly plan = toSignal(this.route.queryParamMap.pipe(
    map(q => q.getAll('maintenance')), distinctUntilChanged((a, b) => a.join() === b.join())), { initialValue: [] as string[] });
  private readonly units = signal<Unit[]>([]);
  protected readonly report = signal<ImpactDto | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly failedAt = signal<string | null>(null);

  protected readonly groups = computed(() => {
    const byGroup = new Map<string, Unit[]>();
    for (const u of this.units()) {
      byGroup.set(u.group, [...(byGroup.get(u.group) ?? []), u]);
    }
    return [...byGroup].map(([group, units]) => ({ name: group.replaceAll('_', ' '), units }));
  });
  private readonly out = computed(() => new Map(
    (this.report()?.outOfService ?? []).filter(o => ALREADY_OUT.includes(o.reason)).map(o => [o.unit, o.reason.toLowerCase()])));

  constructor() {
    inject(BoardApi).board().subscribe({
      next: b => this.units.set(b.equipment.map(e => ({ id: e.id, group: e.group }))),
      error: () => {},
    });
    this.route.queryParamMap.pipe(
      map(q => q.getAll('maintenance')),
      distinctUntilChanged((a, b) => a.join() === b.join()),
      // a new plan or a new tick cancels the request in flight, so only the latest answer lands
      switchMap(units => interval(REFRESH_MS).pipe(startWith(0), switchMap(() => this.api.assess(units).pipe(
        map(r => ({ r })),
        catchError((e: HttpErrorResponse) => of({ e })))))),
      takeUntilDestroyed(),
    ).subscribe(result => {
      if ('r' in result) {
        this.report.set(result.r);
        this.error.set(null);
        this.failedAt.set(null);
      } else if (result.e.status === 400 && result.e.error?.detail) {
        this.error.set(result.e.error.detail);
        this.failedAt.set(null);
      } else {
        this.failedAt.set(utcTime(Date.now()));
      }
    });
  }

  protected isChecked(id: string): boolean {
    return this.plan().includes(id) || this.out().has(id);
  }

  /** The reason a unit is already out, or null when it can be planned. */
  protected reason(id: string): string | null {
    return this.out().get(id) ?? null;
  }

  protected toggle(id: string): void {
    const plan = this.plan();
    this.go(plan.includes(id) ? plan.filter(u => u !== id) : [...plan, id]);
  }

  protected clear(): void {
    this.go([]);
  }

  private go(units: string[]): void {
    void this.router.navigate(['/impact'], { queryParams: { maintenance: units }, replaceUrl: true });
  }
}
