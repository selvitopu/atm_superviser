import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { AdminApi, AdminUserDto } from '../../../core/api/admin-api';
import { AuthService } from '../../../core/auth/auth.service';
import { passwordProblem } from '../../../shared/password-rule';
import { AppHeader } from '../../board/app-header/app-header';

const ROLES = ['ADMIN', 'SUPERVISOR', 'VIEWER'];

/**
 * The accounts, for administrators: role, enabled, and a new password. Each change ends that user's sessions at once
 * on the server. An administrator's own row is read-only, so nobody locks themselves out.
 */
@Component({
  selector: 'app-users-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AppHeader],
  template: `
    <app-app-header />
    <main>
      <h2>USERS</h2>
      <p class="note">New accounts start as VIEWER. A change signs that user out at once.</p>
      @if (disabling(); as u) {
        <div class="confirm" role="group" aria-label="Confirm disabling">
          <span>Disable {{ u }}? They are signed out at once.</span>
          <button type="button" class="go" (click)="change(u, { enabled: false })">Confirm</button>
          <button type="button" (click)="disabling.set(null)">Cancel</button>
        </div>
      }
      @if (message(); as m) {
        <p class="message" [class.error]="m.error" [attr.role]="m.error ? 'alert' : 'status'">{{ m.text }}</p>
      }
      <div class="scroll">
        <table>
          <thead><tr><th>Username</th><th>Name</th><th>Role</th><th>State</th><th></th></tr></thead>
          <tbody>
            @for (u of users(); track u.username) {
              <tr>
                <td class="mono username">{{ u.username }}</td>
                <td>{{ u.displayName }}</td>
                @if (u.username === me()) {
                  <td class="mono">{{ u.role }} <span class="you">(you)</span></td>
                  <td class="mono">{{ u.enabled ? 'Enabled' : 'Disabled' }}</td>
                  <td></td>
                } @else {
                  <td>
                    <select class="role" [attr.aria-label]="'Role of ' + u.username" [disabled]="busy()"
                      (change)="pick(u.username, $any($event.target).value)">
                      @for (r of roles; track r) { <option [value]="r" [selected]="r === roleOf(u)">{{ r }}</option> }
                    </select>
                    <button type="button" [disabled]="busy() || roleOf(u) === u.role"
                      (click)="change(u.username, { role: roleOf(u) })">Save</button>
                  </td>
                  <td class="mono" [class.off]="!u.enabled">{{ u.enabled ? 'Enabled' : 'Disabled' }}</td>
                  <td class="actions">
                    @if (u.enabled) {
                      <button type="button" [disabled]="busy()" (click)="disabling.set(u.username)">Disable</button>
                    } @else {
                      <button type="button" [disabled]="busy()" (click)="change(u.username, { enabled: true })">Enable</button>
                    }
                    @if (passwordFor() === u.username) {
                      <input type="password" class="password" autocomplete="new-password"
                        [attr.aria-label]="'New password for ' + u.username" (input)="password.set($any($event.target).value)" />
                      <button type="button" [disabled]="busy()" (click)="setPassword(u.username)">Set</button>
                      <button type="button" (click)="passwordFor.set(null)">Cancel</button>
                    } @else {
                      <button type="button" [disabled]="busy()" (click)="openPassword(u.username)">Set password</button>
                    }
                  </td>
                }
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
    h2 { margin: 0; font: 600 13px var(--font-mono); letter-spacing: 0.08em; }
    .note { margin: 0; font-size: 12px; color: var(--muted); }
    .confirm { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border: 1px solid var(--degraded);
      border-radius: 3px; font-size: 12px; }
    .go { border-color: var(--degraded); color: var(--degraded); }
    .message { margin: 0; font-size: 12px; color: var(--ok); }
    .message.error { color: var(--failed); }
    .scroll { flex: 1; overflow: auto; background: var(--panel); border: 1px solid var(--panel-2); }
    table { width: 100%; border-collapse: collapse; font-size: 12px; }
    th { position: sticky; top: 0; background: var(--panel); text-align: left; color: var(--muted); font-weight: 600; }
    th, td { padding: 4px 8px; border-bottom: 1px solid var(--panel-2); vertical-align: middle; }
    .mono { font-family: var(--font-mono); }
    .you { color: var(--muted); }
    .off { color: var(--muted); }
    .actions { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
    button, select, input { padding: 2px 8px; color: var(--text); background: var(--panel-2); border: 1px solid var(--muted);
      border-radius: 3px; font: 600 11px var(--font-mono); color-scheme: dark; }
    button { cursor: pointer; }
    button:disabled, select:disabled { opacity: 0.4; cursor: default; }
    button:focus-visible, select:focus-visible, input:focus-visible { outline: 2px solid var(--focus); outline-offset: 1px; }
  `,
})
export class UsersPage {
  private readonly api = inject(AdminApi);
  protected readonly roles = ROLES;
  private readonly auth = inject(AuthService);
  protected readonly me = computed(() => this.auth.user()?.username ?? null);

  protected readonly users = signal<AdminUserDto[]>([]);
  private readonly picked = signal<Record<string, string>>({});
  protected readonly roleOf = (u: AdminUserDto) => this.picked()[u.username] ?? u.role;
  protected readonly disabling = signal<string | null>(null);
  protected readonly passwordFor = signal<string | null>(null);
  protected readonly password = signal('');
  protected readonly busy = signal(false);
  protected readonly message = signal<{ text: string; error: boolean } | null>(null);

  constructor() {
    this.api.users().subscribe({
      next: list => this.users.set(list),
      error: (e: HttpErrorResponse) => this.message.set({ text: e.error?.detail ?? 'The accounts could not be loaded.', error: true }),
    });
  }

  protected pick(username: string, role: string): void {
    this.picked.update(p => ({ ...p, [username]: role }));
  }

  protected openPassword(username: string): void {
    this.password.set('');
    this.message.set(null);
    this.passwordFor.set(username);
  }

  protected change(username: string, change: { role?: string; enabled?: boolean }): void {
    this.disabling.set(null);
    this.run(this.api.change(username, change), (updated: AdminUserDto) => {
      this.users.update(list => list.map(u => u.username === updated.username ? updated : u));
      this.picked.update(p => { const { [username]: _, ...rest } = p; return rest; });
      return change.role ? `${username} is now ${updated.role}; their sessions have ended.`
        : updated.enabled ? `${username} is now enabled.` : `${username} is now disabled; their sessions have ended.`;
    });
  }

  protected setPassword(username: string): void {
    const problem = passwordProblem(username, this.password());
    if (problem) {
      this.message.set({ text: problem, error: true });
      return;
    }
    this.run(this.api.setPassword(username, this.password()), () => {
      this.passwordFor.set(null);
      this.password.set('');
      return `${username} has a new password; their sessions have ended.`;
    });
  }

  private run<T>(request: Observable<T>, done: (value: T) => string): void {
    this.busy.set(true);
    this.message.set(null);
    request.subscribe({
      next: value => { this.busy.set(false); this.message.set({ text: done(value), error: false }); },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        this.message.set({ text: e.error?.detail ?? 'The change failed.', error: true });
      },
    });
  }
}
