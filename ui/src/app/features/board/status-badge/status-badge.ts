import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Health, ServiceStatus } from '../../../core/api/models';

const SPEC: Record<Health | ServiceStatus, { icon: string; cls: string }> = {
  OPERATIONAL: { icon: '✓', cls: 'ok' },
  OK: { icon: '✓', cls: 'ok' },
  DEGRADED: { icon: '▲', cls: 'degraded' },
  FAILED: { icon: '✕', cls: 'failed' },
  UNKNOWN: { icon: '?', cls: 'unknown' },
};

/** Status as icon + label + colour, so colour is never the only signal. UNKNOWN is never green. */
@Component({
  selector: 'app-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class]': 'cssClass()' },
  template: `<span class="icon" aria-hidden="true">{{ icon() }}</span>&ngsp;<span>{{ label() }}</span>`,
  styles: `
    :host { display: inline-flex; align-items: center; gap: 0.4em; padding: 1px 6px; border: 1px solid currentColor;
      border-radius: 3px; font: 600 12px/1.5 var(--font-mono); white-space: nowrap; }
    :host(.ok) { color: var(--ok); }
    :host(.degraded) { color: var(--degraded); }
    :host(.failed) { color: var(--failed); }
    :host(.unknown) { color: var(--unknown);
      background: repeating-linear-gradient(135deg, transparent 0 4px, rgb(199 74 216 / 0.22) 4px 8px); }
  `,
})
export class StatusBadge {
  readonly status = input.required<Health | ServiceStatus>();
  readonly bestCase = input<ServiceStatus | null>(null);

  protected readonly icon = computed(() => SPEC[this.status()].icon);
  protected readonly cssClass = computed(() => SPEC[this.status()].cls);
  protected readonly label = computed(() => {
    const status = this.status();
    const best = this.bestCase();
    return status === 'UNKNOWN' && best ? `UNKNOWN · at best ${best}` : status;
  });
}
