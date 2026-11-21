import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { Severity, TimelineDto, TimelineMarkerDto, TimelineSpanDto } from '../../../core/api/models';
import { formatUtc } from '../../../shared/format';

/** The marker height says the severity as well as the colour does. */
const HEIGHT: Record<Severity, number> = { CRITICAL: 100, MAJOR: 80, MINOR: 60, WARNING: 45, INDETERMINATE: 35, CLEARED: 25 };

interface Placed {
  left: number;
  width: number;
}

/**
 * The window as a bar: a marker per alarm raised, shaded flood and gap spans, restarts and the play head. Clicking the
 * bar, or pressing an arrow key on it, asks for that instant; the page decides what to do with it.
 */
@Component({
  selector: 'app-replay-timeline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="timeline">
      <div class="track" role="slider" tabindex="0" aria-label="Replay position"
        [attr.aria-valuemin]="from()" [attr.aria-valuemax]="to()" [attr.aria-valuenow]="at()"
        [attr.aria-valuetext]="time(at())" (click)="clicked($event)" (keydown)="key($event)">
        @for (s of floods(); track $index) {
          <span class="span flood" title="Alarm flood" [style.left.%]="s.left" [style.width.%]="s.width"></span>
        }
        @for (s of gaps(); track $index) {
          <span class="span gap" title="No recorded data" [style.left.%]="s.left" [style.width.%]="s.width"></span>
        }
        @for (r of restarts(); track $index) {
          <span class="restart" title="Supervisor restart" [style.left.%]="r"></span>
        }
        <span class="head" [style.left.%]="head()"></span>
      </div>
      @for (m of markers(); track $index) {
        <button type="button" class="marker" [class]="'marker sev-' + m.severity.toLowerCase()"
          [class.suppressed]="m.suppressed" [style.left.%]="m.left" [style.height.%]="m.height"
          [attr.aria-label]="m.name" [attr.title]="m.name" (click)="seek.emit(m.ms)"></button>
      }
    </div>
    <p class="legend" aria-hidden="true">
      <span class="key flood"></span> flood <span class="key gap"></span> no recorded data
      <span class="key restart"></span> restart
    </p>
    @if (data()?.truncated) {
      <p class="cap" role="status">Too many alarms to mark; showing the newest 2,000</p>
    }
  `,
  styles: `
    :host { display: block; padding: 6px 16px; }
    .timeline { position: relative; height: 36px; }
    .track { position: absolute; inset: 0; overflow: hidden; cursor: pointer; background: var(--panel);
      border: 1px solid var(--muted); border-radius: 3px; }
    .track:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
    .span { position: absolute; top: 0; bottom: 0; }
    .flood, .key.flood { background: repeating-linear-gradient(45deg, var(--degraded) 0 3px, transparent 3px 7px);
      opacity: 0.55; }
    .gap, .key.gap { background: repeating-linear-gradient(0deg, var(--unknown) 0 2px, transparent 2px 5px);
      opacity: 0.5; }
    .restart, .key.restart { position: absolute; top: 0; bottom: 0; width: 0; border-left: 2px dotted var(--text); }
    .head { position: absolute; top: 0; bottom: 0; width: 2px; margin-left: -1px; background: var(--focus); }
    .marker { position: absolute; bottom: 0; width: 7px; margin-left: -3px; padding: 0; cursor: pointer;
      background: var(--muted); border: 1px solid var(--bg); border-radius: 2px 2px 0 0; }
    .marker.sev-critical { background: var(--failed); }
    .marker.sev-major { background: var(--degraded); }
    .marker.sev-minor, .marker.sev-warning { background: var(--text); }
    .marker.suppressed { background: transparent; border: 1px dashed var(--muted); }
    .marker:focus-visible { outline: 2px solid var(--focus); outline-offset: 1px; z-index: 2; }
    .legend { margin: 4px 0 0; display: flex; gap: 8px; align-items: center; font: 11px var(--font-mono);
      color: var(--muted); }
    .key { display: inline-block; position: relative; width: 18px; height: 10px; border: 1px solid var(--muted); }
    .key.restart { width: 6px; border-top: 0; border-bottom: 0; border-right: 0; }
    .cap { margin: 4px 0 0; font: 600 12px var(--font-mono); color: var(--degraded); }
  `,
})
export class ReplayTimeline {
  /** The window and the replayed instant, in epoch milliseconds. */
  readonly from = input.required<number>();
  readonly to = input.required<number>();
  readonly at = input.required<number>();
  readonly data = input<TimelineDto | null>(null);
  /** The instant, in epoch milliseconds, that the viewer asked for. */
  readonly seek = output<number>();

  protected readonly time = formatUtc;

  private pct(ms: number): number {
    const span = this.to() - this.from();
    return span <= 0 ? 0 : Math.min(100, Math.max(0, ((ms - this.from()) / span) * 100));
  }

  protected readonly head = computed(() => this.pct(this.at()));

  protected readonly markers = computed(() => (this.data()?.markers ?? []).map((m: TimelineMarkerDto) => ({
    ms: Date.parse(m.at),
    left: this.pct(Date.parse(m.at)),
    height: HEIGHT[m.severity] ?? 50,
    severity: m.severity,
    suppressed: m.suppressed,
    name: `${formatUtc(m.at)} ${m.severity} ${m.equipmentId} ${m.text}${m.suppressed ? ' (suppressed)' : ''}`,
  })));
  protected readonly floods = computed(() => this.placed(this.data()?.floods ?? []));
  protected readonly gaps = computed(() => this.placed(this.data()?.gaps ?? []));
  protected readonly restarts = computed(() => (this.data()?.restarts ?? []).map(r => this.pct(Date.parse(r))));

  /** A span keeps its true start in the data, so it is clipped to the window here; an open span runs to the end. */
  private placed(spans: TimelineSpanDto[]): Placed[] {
    const from = this.from();
    const to = this.to();
    return spans
      .map(s => ({ start: Math.max(Date.parse(s.start), from), end: Math.min(s.end ? Date.parse(s.end) : to, to) }))
      .filter(s => s.end > s.start)
      .map(s => ({ left: this.pct(s.start), width: this.pct(s.end) - this.pct(s.start) }));
  }

  protected clicked(event: MouseEvent): void {
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
    if (rect.width > 0) {
      const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
      this.seek.emit(Math.round(this.from() + ratio * (this.to() - this.from())));
    }
  }

  /** Arrow keys move by a fiftieth of the window. */
  protected key(event: KeyboardEvent): void {
    const step = (this.to() - this.from()) / 50;
    if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault();
      const at = this.at() + (event.key === 'ArrowRight' ? step : -step);
      this.seek.emit(Math.round(Math.min(this.to(), Math.max(this.from(), at))));
    }
  }
}
