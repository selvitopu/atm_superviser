import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ServiceStatus } from '../../../core/api/models';
import { BoardSource } from '../../../core/state/board-source';
import { StatusBadge } from '../status-badge/status-badge';

@Component({
  selector: 'app-service-strip',
  imports: [StatusBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="strip">
      @for (s of source.board()?.services ?? []; track s.id) {
        <span class="chip" [attr.title]="s.reasons.join('; ') || null">
          <span class="id">{{ s.id }}</span>
          <app-status-badge [status]="s.status" [bestCase]="best(s.bestCase)" />
        </span>
      }
    </div>
  `,
  styles: `
    .strip { display: flex; gap: 12px; padding: 8px 16px; }
    .chip { display: inline-flex; align-items: center; gap: 8px; padding: 4px 10px; background: var(--panel);
      border: 1px solid var(--panel-2); border-radius: 4px; }
    .id { font: 600 13px var(--font-mono); }
  `,
})
export class ServiceStrip {
  protected readonly source = inject(BoardSource);

  protected best(value: string): ServiceStatus {
    return value as ServiceStatus;
  }
}
