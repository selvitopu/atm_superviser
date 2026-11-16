import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { BoardApi, EquipmentCommand } from '../../../core/api/board-api';
import { EquipmentDto } from '../../../core/api/models';
import { AuthService } from '../../../core/auth/auth.service';

const LABEL: Record<EquipmentCommand, string> = {
  MAINTENANCE_ON: 'Maintenance on', MAINTENANCE_OFF: 'Maintenance off', RESTART: 'Restart', MAKE_ACTIVE: 'Make active',
};

/** The question the confirm bar asks: it names the unit and what will happen to it. */
function question(command: EquipmentCommand, id: string): string {
  switch (command) {
    case 'MAINTENANCE_ON': return `Put ${id} into maintenance?`;
    case 'MAINTENANCE_OFF': return `Take ${id} out of maintenance?`;
    case 'RESTART': return `Restart ${id}? It is out of service until the restart ends.`;
    case 'MAKE_ACTIVE': return `Make ${id} the active unit? Its partner becomes standby.`;
  }
}

/**
 * Equipment control for supervisors. A command is armed first and sent only on Confirm, one at a time; the unit
 * decides whether it fits its state, and a refusal shows the server's reason.
 */
@Component({
  selector: 'app-equipment-control',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (canControl()) {
      <section aria-label="Control">
        <h3>Control</h3>
        <div class="buttons">
          <button type="button" [disabled]="busy()" (click)="arm(maintenanceCommand())">{{ label(maintenanceCommand()) }}</button>
          <button type="button" [disabled]="busy() || !equipment().maintenance" (click)="arm('RESTART')"
            [title]="equipment().maintenance ? '' : 'Restart needs maintenance mode'">Restart</button>
          @if (standbyPairMember()) {
            <button type="button" [disabled]="busy()" (click)="arm('MAKE_ACTIVE')">Make active</button>
          }
        </div>
        @if (armed(); as command) {
          @if (!pending()) {
            <div class="confirm" role="group" aria-label="Confirm command">
              <span>{{ question(command, equipment().id) }}</span>
              <button type="button" class="go" (click)="send(command)">Confirm</button>
              <button type="button" (click)="armed.set(null)">Cancel</button>
            </div>
          }
        }
        @if (pending()) {
          <p class="note" role="status">Sending…</p>
        }
        @if (message(); as m) {
          <p class="note" [class.error]="m.error" [attr.role]="m.error ? 'alert' : 'status'">{{ m.text }}</p>
        }
      </section>
    }
  `,
  styles: `
    section { margin-top: 12px; padding-top: 8px; border-top: 1px solid var(--panel-2); }
    h3 { margin: 0 0 6px; font: 600 11px var(--font-mono); color: var(--muted); letter-spacing: 0.08em;
      text-transform: uppercase; }
    .buttons { display: flex; flex-wrap: wrap; gap: 6px; }
    button { padding: 3px 10px; color: var(--text); background: var(--panel-2); border: 1px solid var(--muted);
      border-radius: 3px; font: 600 12px var(--font-mono); cursor: pointer; }
    button:disabled { opacity: 0.4; cursor: default; }
    button:focus-visible { outline: 2px solid var(--focus); outline-offset: 1px; }
    .confirm { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 8px; padding: 6px 8px;
      border: 1px solid var(--degraded); border-radius: 3px; font-size: 12px; }
    .go { border-color: var(--degraded); color: var(--degraded); }
    .note { margin: 6px 0 0; font-size: 12px; color: var(--muted); }
    .error { color: var(--failed); }
  `,
})
export class EquipmentControl {
  readonly equipment = input.required<EquipmentDto>();
  /** A command was answered, accepted or not: the server has recorded it in the unit's events. */
  readonly commanded = output<void>();

  private readonly api = inject(BoardApi);
  private readonly auth = inject(AuthService);
  protected readonly question = question;
  protected readonly label = (c: EquipmentCommand) => LABEL[c];

  protected readonly canControl = computed(() => this.auth.hasRole('SUPERVISOR'));
  protected readonly maintenanceCommand = computed<EquipmentCommand>(() =>
    this.equipment().maintenance ? 'MAINTENANCE_OFF' : 'MAINTENANCE_ON');
  protected readonly standbyPairMember = computed(() => !!this.equipment().pair && this.equipment().role === 'STANDBY');

  private readonly unitId = computed(() => this.equipment().id);
  /** A confirmation or result belongs to one unit: selecting another clears it. */
  protected readonly armed = linkedSignal<string, EquipmentCommand | null>({ source: this.unitId, computation: () => null });
  protected readonly message = linkedSignal<string, { text: string; error: boolean } | null>({
    source: this.unitId, computation: () => null,
  });
  protected readonly pending = signal(false);
  protected readonly busy = computed(() => this.pending());

  protected arm(command: EquipmentCommand): void {
    this.message.set(null);
    this.armed.set(command);
  }

  protected send(command: EquipmentCommand): void {
    if (this.pending()) {
      return;
    }
    this.pending.set(true);
    this.api.command(this.equipment().id, command).subscribe({
      next: () => this.done({ text: `${LABEL[command]}: done`, error: false }),
      error: (e: HttpErrorResponse) => this.done({ text: e.error?.detail ?? `${LABEL[command]} failed`, error: true }),
    });
  }

  private done(message: { text: string; error: boolean }): void {
    this.pending.set(false);
    this.armed.set(null);
    this.message.set(message);
    this.commanded.emit();
  }
}
