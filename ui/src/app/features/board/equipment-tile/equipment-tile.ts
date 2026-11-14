import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { EquipmentDto } from '../../../core/api/models';
import { Clock } from '../../../core/state/clock';
import { freshness } from '../../../core/state/freshness';
import { display, formatUtc } from '../../../shared/format';
import { StatusBadge } from '../status-badge/status-badge';

const REASON_LABEL: Record<string, string> = {
  AWAITING_STATUS: 'awaiting status',
  STATUS_UNAVAILABLE: 'status unavailable',
  NOT_YET_POLLED: 'not yet polled',
  SUPERVISOR_RESTARTED: 'supervisor restarted',
  RESTARTING: 'restarting',
};

const SUMMARY: Record<string, [string, string][]> = {
  RADAR: [['Plots/scan', 'plotsPerScan'], ['Rotation ms', 'rotationPeriodMs']],
  SDPS: [['Tracks', 'trackCount'], ['Latency ms', 'latencyMs']],
  FDPS: [['Plans', 'flightPlanCount'], ['Queue', 'queueDepth']],
};

@Component({
  selector: 'app-equipment-tile',
  imports: [RouterLink, RouterLinkActive, StatusBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="tile" [class]="tileClass()" [routerLink]="['/board', equipment().id]" routerLinkActive="selected">
      <span class="top">
        <span class="id">{{ equipment().id }}</span>
        @if (roleLabel(); as role) {
          <span class="role">{{ role }}</span>
        }
      </span>
      <span class="badges">
        <app-status-badge [status]="equipment().health" />
        @if (equipment().maintenance) {
          <span class="maint">MAINT</span>
        }
      </span>
      @if (lostContact()) {
        <span class="note">last contact {{ lastContact() }}</span>
      } @else if (reasonLabel(); as reason) {
        <span class="note reason">{{ reason }}</span>
      }
      @if (staleAgeSeconds() !== null) {
        <span class="age degraded">{{ staleAgeSeconds() }} s ago</span>
      }
      <span class="values" [class.dim]="dimmed()">
        @for (v of summary(); track v.label) {
          <span class="value"><span class="k">{{ v.label }}</span><span class="v">{{ v.value }}</span></span>
        }
      </span>
    </a>
  `,
  styles: `
    .tile { display: flex; flex-direction: column; gap: 6px; width: 190px; box-sizing: border-box; padding: 8px 10px;
      color: inherit; text-decoration: none; background: var(--panel); border: 1px solid var(--panel-2);
      border-left: 4px solid var(--muted); border-radius: 4px; }
    .tile.h-operational { border-left-color: var(--ok); }
    .tile.h-degraded { border-left-color: var(--degraded); }
    .tile.h-failed { border-left-color: var(--failed); }
    .tile.h-unknown { border-left-color: var(--unknown);
      background: repeating-linear-gradient(135deg, var(--panel) 0 6px, var(--panel-2) 6px 12px); }
    .tile.selected { outline: 1px solid var(--focus); }
    .tile:focus-visible { outline: 2px solid var(--focus); outline-offset: 1px; }
    .top { display: flex; justify-content: space-between; align-items: baseline; }
    .id { font: 700 14px var(--font-mono); }
    .role { font: 600 11px var(--font-mono); color: var(--muted); letter-spacing: 0.06em; }
    .badges { display: flex; gap: 6px; align-items: center; }
    .maint { padding: 1px 6px; border: 1px solid var(--maint); border-radius: 3px; color: var(--maint);
      font: 600 12px/1.5 var(--font-mono); }
    .note, .age { font: 12px var(--font-mono); color: var(--muted); }
    .age.degraded { color: var(--degraded); }
    .values { display: flex; flex-direction: column; gap: 2px; font: 12px var(--font-mono); }
    .value { display: flex; justify-content: space-between; gap: 12px; }
    .values.dim { opacity: 0.4; }
    .k { color: var(--muted); }
  `,
})
export class EquipmentTile {
  readonly equipment = input.required<EquipmentDto>();
  private readonly clock = inject(Clock);

  protected readonly tileClass = computed(() => `tile h-${this.equipment().health.toLowerCase()}`);
  protected readonly roleLabel = computed(() => {
    const e = this.equipment();
    return e.pair && (e.role === 'ACTIVE' || e.role === 'STANDBY') ? e.role : null;
  });
  protected readonly lostContact = computed(
    () => this.equipment().health === 'UNKNOWN' && this.equipment().unknownReason === 'LOST_CONTACT');
  protected readonly lastContact = computed(() => formatUtc(this.equipment().contact.lastContactAt));
  protected readonly reasonLabel = computed(() => {
    const e = this.equipment();
    return e.health === 'UNKNOWN' && e.unknownReason ? (REASON_LABEL[e.unknownReason] ?? null) : null;
  });
  protected readonly dimmed = computed(
    () => this.lostContact() || (this.equipment().health === 'UNKNOWN' && this.equipment().unknownReason === 'STATUS_UNAVAILABLE'));
  protected readonly staleAgeSeconds = computed(() => {
    if (this.lostContact()) {
      return null;
    }
    const c = this.equipment().contact;
    const f = freshness(c.lastPollAt, c.pollIntervalMs, this.clock.serverNow());
    return f.stale && f.ageMs !== null ? Math.round(f.ageMs / 1000) : null;
  });
  protected readonly summary = computed(() => {
    const e = this.equipment();
    return (SUMMARY[e.type] ?? []).map(([label, key]) => ({ label, value: display(e.values[key]) }));
  });
}
