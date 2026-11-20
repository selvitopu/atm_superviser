import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, interval } from 'rxjs';
import { SimDeviceDto, SimulatorApi } from '../../../core/api/simulator-api';
import { AppHeader } from '../../board/app-header/app-header';

const REFRESH_MS = 2000;
const LABEL: Record<string, string> = {
  FAIL: 'Fail', DEGRADE: 'Degrade', CHANNEL_A_FAIL: 'Channel A fail', CHANNEL_B_FAIL: 'Channel B fail',
  SENSOR_LOST: 'Sensor lost', UNREACHABLE: 'Unreachable', DROP_TRAPS: 'Drop traps', RECOVER: 'Recover',
};
/** The faults each simulated type supports, in the order the buttons show them. */
const FAULTS: Record<string, string[]> = {
  RADAR: ['FAIL', 'DEGRADE', 'CHANNEL_A_FAIL', 'CHANNEL_B_FAIL', 'UNREACHABLE', 'DROP_TRAPS', 'RECOVER'],
  SDPS: ['FAIL', 'DEGRADE', 'SENSOR_LOST', 'UNREACHABLE', 'DROP_TRAPS', 'RECOVER'],
  FDPS: ['FAIL', 'DEGRADE', 'UNREACHABLE', 'DROP_TRAPS', 'RECOVER'],
};

/** The training console: inject faults into the simulated equipment and watch the board react. Demo mode only. */
@Component({
  selector: 'app-simulator-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AppHeader],
  template: `
    <app-app-header />
    <main>
      <div class="bar">
        <h2>SIMULATOR</h2>
        <p class="note">Training faults go to the simulated equipment of the demo centre only. An alarm flood shows on the board.</p>
        <button type="button" [disabled]="busy()" (click)="confirmReset.set(true)">Reset all</button>
      </div>
      @if (confirmReset()) {
        <div class="confirm" role="group" aria-label="Confirm reset">
          <span>Clear every fault and restore the default roles?</span>
          <button type="button" class="go" (click)="reset()">Confirm</button>
          <button type="button" (click)="confirmReset.set(false)">Cancel</button>
        </div>
      }
      @if (error(); as e) {
        <p class="error" role="alert">{{ e }}</p>
      }
      <div class="scroll">
        <table>
          <thead>
            <tr><th>Device</th><th>Status</th><th>Role</th><th>Answers</th><th>Faults</th><th>Inject</th></tr>
          </thead>
          <tbody>
            @for (d of devices(); track d.id) {
              <tr>
                <td class="mono id">{{ d.id }}</td>
                <td class="mono">{{ d.status }}@if (d.maintenance) { · MAINT}</td>
                <td class="mono">{{ d.role }}</td>
                <td class="mono">{{ d.responding ? 'yes' : 'no' }}</td>
                <td class="mono faults">{{ d.faults.join(', ') || '—' }}</td>
                <td class="inject">
                  @for (f of faults(d); track f) {
                    @if (f === 'SENSOR_LOST') {
                      <select class="sensor" [attr.aria-label]="'Sensor for ' + d.id"
                        (change)="sensor.set($any($event.target).value)">
                        @for (r of radars(); track r) { <option [value]="r" [selected]="r === sensorChoice()">{{ r }}</option> }
                      </select>
                    }
                    <button type="button" [disabled]="busy()" (click)="inject(d, f)">{{ label(f) }}</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </main>
  `,
  styles: `
    :host { display: flex; flex-direction: column; height: 100vh; }
    main { flex: 1; display: flex; flex-direction: column; gap: 12px; padding: 16px; min-height: 0; }
    .bar { display: flex; align-items: center; gap: 16px; }
    h2 { margin: 0; font: 600 13px var(--font-mono); letter-spacing: 0.08em; }
    .note { flex: 1; margin: 0; font-size: 12px; color: var(--muted); }
    .confirm { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border: 1px solid var(--degraded);
      border-radius: 3px; font-size: 12px; }
    .go { border-color: var(--degraded); color: var(--degraded); }
    .error { margin: 0; color: var(--failed); font-size: 12px; }
    .scroll { flex: 1; overflow: auto; background: var(--panel); border: 1px solid var(--panel-2); }
    table { width: 100%; border-collapse: collapse; font-size: 12px; }
    th { position: sticky; top: 0; background: var(--panel); text-align: left; color: var(--muted); font-weight: 600; }
    th, td { padding: 4px 8px; border-bottom: 1px solid var(--panel-2); vertical-align: middle; }
    .mono { font-family: var(--font-mono); }
    .inject { display: flex; flex-wrap: wrap; gap: 4px; }
    button, select { padding: 2px 8px; color: var(--text); background: var(--panel-2); border: 1px solid var(--muted);
      border-radius: 3px; font: 600 11px var(--font-mono); cursor: pointer; color-scheme: dark; }
    button:disabled { opacity: 0.4; cursor: default; }
    button:focus-visible, select:focus-visible { outline: 2px solid var(--focus); outline-offset: 1px; }
  `,
})
export class SimulatorPage {
  private readonly api = inject(SimulatorApi);
  protected readonly label = (f: string) => LABEL[f] ?? f;
  protected readonly faults = (d: SimDeviceDto) => FAULTS[d.type] ?? [];

  protected readonly devices = signal<SimDeviceDto[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly confirmReset = signal(false);
  protected readonly radars = computed(() => this.devices().filter(d => d.type === 'RADAR').map(d => d.id));
  protected readonly sensor = signal<string | null>(null);
  /** The sensor the next "Sensor lost" names: the one picked, else the first radar. */
  protected readonly sensorChoice = computed(() => this.sensor() ?? this.radars()[0] ?? null);
  private loading = false;

  constructor() {
    this.load();
    interval(REFRESH_MS).pipe(takeUntilDestroyed()).subscribe(() => this.load());
  }

  protected inject(d: SimDeviceDto, fault: string): void {
    const target = fault === 'SENSOR_LOST' ? this.sensorChoice() : null;
    this.run(this.api.fault(d.id, fault, target), (updated: SimDeviceDto) =>
      this.devices.update(list => list.map(x => x.id === updated.id ? updated : x)));
  }

  protected reset(): void {
    this.confirmReset.set(false);
    this.run(this.api.reset(), () => this.load());
  }

  private run<T>(request: Observable<T>, then: (value: T) => void): void {
    this.busy.set(true);
    this.error.set(null);
    request.subscribe({
      next: value => { this.busy.set(false); then(value); },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(e.error?.detail ?? 'The simulator did not accept that.');
      },
    });
  }

  private load(): void {
    if (this.loading) {
      return;
    }
    this.loading = true;
    this.api.devices().subscribe({
      next: list => { this.loading = false; this.devices.set(list); },
      error: (e: HttpErrorResponse) => {
        this.loading = false;
        this.error.set(e.error?.detail ?? 'The simulator console is not available.');
      },
    });
  }
}
