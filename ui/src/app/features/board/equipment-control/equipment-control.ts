import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy, Component, ElementRef, afterRenderEffect, computed, inject, input, linkedSignal, output,
  signal, viewChild,
} from '@angular/core';
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

/** Why each command cannot be sent now, as the unit would refuse it; empty when it can. */
type Blocked = Record<EquipmentCommand, string>;

let nextId = 0;

/**
 * Equipment control for supervisors. A command is armed first and sent only on Confirm, one at a time. The panel
 * offers a command only when the unit's state allows it, the unit decides again, and a refusal shows the server's
 * reason. A confirmation lasts while its command still fits the unit, and every result names its unit.
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
          <button type="button" [disabled]="busy() || !!blocked().RESTART" (click)="arm('RESTART')"
            [title]="blocked().RESTART">Restart</button>
          @if (standbyPairMember()) {
            <button type="button" [disabled]="busy() || !!blocked().MAKE_ACTIVE" (click)="arm('MAKE_ACTIVE')"
              [title]="blocked().MAKE_ACTIVE">Make active</button>
          }
        </div>
        @if (armed(); as command) {
          @if (!pending()) {
            <div class="confirm" role="group" [attr.aria-labelledby]="questionId" aria-live="polite">
              <span [id]="questionId">{{ question(command, equipment().id) }}</span>
              <button #confirm type="button" class="go" [attr.aria-describedby]="questionId"
                (click)="send(command)">Confirm</button>
              <button type="button" (click)="armed.set(null)">Cancel</button>
            </div>
          }
        }
        @if (pending(); as p) {
          <p class="note" role="status">Sending {{ label(p.command) }} to {{ p.id }}…</p>
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
  protected readonly blocked = computed<Blocked>(() => {
    const e = this.equipment();
    return {
      MAINTENANCE_ON: e.maintenance ? 'Already in maintenance' : '',
      MAINTENANCE_OFF: e.maintenance ? '' : 'Not in maintenance',
      RESTART: !e.maintenance ? 'Restart needs maintenance mode'
        : e.values['lastCommandResult'] === 'IN_PROGRESS' ? 'A restart is in progress' : '',
      MAKE_ACTIVE: this.standbyPairMember() && e.health === 'OPERATIONAL' && !e.maintenance ? ''
        : 'Make active needs a standby unit that is operational and not in maintenance',
    };
  });

  private readonly unitId = computed(() => this.equipment().id);
  /** Cleared when another unit is selected, or when the unit's state no longer allows the command. */
  protected readonly armed = linkedSignal<{ id: string; blocked: Blocked }, EquipmentCommand | null>({
    source: () => ({ id: this.unitId(), blocked: this.blocked() }),
    computation: (now, previous) => {
      const command = previous?.value;
      return command && previous.source.id === now.id && !now.blocked[command] ? command : null;
    },
  });
  /** A result names its unit: it may arrive after another unit was selected. Selecting another unit clears it. */
  protected readonly message = linkedSignal<string, { text: string; error: boolean } | null>({
    source: this.unitId, computation: () => null,
  });
  protected readonly pending = signal<{ id: string; command: EquipmentCommand } | null>(null);
  protected readonly busy = computed(() => !!this.pending());

  protected readonly questionId = `confirm-question-${nextId++}`;
  private readonly confirmButton = viewChild<ElementRef<HTMLButtonElement>>('confirm');

  constructor() {
    // the confirm bar takes the focus, so the keyboard and a screen reader land on the question it asks
    afterRenderEffect(() => this.confirmButton()?.nativeElement.focus());
  }

  protected arm(command: EquipmentCommand): void {
    this.message.set(null);
    this.armed.set(command);
  }

  protected send(command: EquipmentCommand): void {
    if (this.pending()) {
      return;
    }
    const id = this.equipment().id;
    this.pending.set({ id, command });
    this.api.command(id, command).subscribe({
      next: () => this.done({ text: `${id}: ${LABEL[command]} done`, error: false }),
      error: (e: HttpErrorResponse) =>
        this.done({ text: `${id}: ${e.error?.detail ?? `${LABEL[command]} failed`}`, error: true }),
    });
  }

  private done(message: { text: string; error: boolean }): void {
    this.pending.set(null);
    this.armed.set(null);
    this.message.set(message);
    this.commanded.emit();
  }
}
