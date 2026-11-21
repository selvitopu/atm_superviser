import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { formatUtc } from '../../../shared/format';

/**
 * The band that says this is a replay (hazard H17): fixed at the top, never dismissible, with no control of any kind
 * but the way back to the live board. It is amber and striped, and its text says what the colour says.
 */
@Component({
  selector: 'app-replay-band',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="band" role="banner">
      <span class="centre">ATM SUPERVISOR · DEMO ACC</span>
      <strong class="copy">REPLAY · {{ when() }} · NOT LIVE · values ≤30 s · no freshness ages</strong>
      <a class="back" routerLink="/board">Back to live</a>
    </div>
  `,
  styles: `
    :host { display: block; position: sticky; top: 0; z-index: 10; }
    .band { display: flex; align-items: center; gap: 24px; padding: 6px 16px; color: var(--bg);
      background: repeating-linear-gradient(135deg, var(--degraded) 0 14px, #b88a1c 14px 28px);
      border-bottom: 2px solid var(--degraded); }
    .centre { font: 600 12px var(--font-mono); letter-spacing: 0.08em; }
    .copy { flex: 1; font: 800 14px var(--font-mono); letter-spacing: 0.06em; }
    .back { padding: 3px 12px; color: var(--text); background: var(--bg); border: 1px solid var(--bg);
      border-radius: 3px; font: 700 12px var(--font-mono); text-decoration: none; }
    .back:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
  `,
})
export class ReplayBand {
  /** The replayed instant and the window, in epoch milliseconds. */
  readonly at = input.required<number>();
  readonly from = input.required<number>();
  readonly to = input.required<number>();

  /** The time, with the date first when the window spans more than one UTC day. */
  protected readonly when = computed(() => {
    const day = (ms: number) => new Date(ms).toISOString().slice(0, 10);
    const time = formatUtc(this.at());
    return day(this.from()) === day(this.to()) ? time : `${day(this.at())} ${time}`;
  });
}
