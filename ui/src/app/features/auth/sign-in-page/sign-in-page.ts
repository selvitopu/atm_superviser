import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DemoAccount } from '../../../core/api/models';
import { AuthService } from '../../../core/auth/auth.service';

/** Only a path inside this application may be returned to after sign-in, never another site. */
function safeReturnUrl(url: string | null): string {
  return url && url.startsWith('/') && !url.startsWith('//') ? url : '/board';
}

@Component({
  selector: 'app-sign-in-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  styleUrl: '../auth-panel.css',
  template: `
    <div class="layout">
      <section class="panel" aria-labelledby="sign-in-title">
        <p class="brand"><span class="mark" aria-hidden="true">◆</span>ATM SUPERVISOR · DEMO ACC</p>
        <p class="sub">Technical supervision</p>
        <h1 id="sign-in-title">Sign in</h1>
        @if (expired) {
          <p class="notice" role="status">Your session ended. Sign in again.</p>
        }
        <form (submit)="submit($event)" novalidate>
          <label for="username">Username</label>
          <input id="username" name="username" autocomplete="username" autocapitalize="none" spellcheck="false"
            [value]="username()" (input)="username.set(valueOf($event))" />
          <label for="password">Password</label>
          <input id="password" name="password" type="password" autocomplete="current-password"
            [value]="password()" (input)="password.set(valueOf($event))" />
          <p class="error" role="alert" aria-live="assertive">{{ error() }}</p>
          <button type="submit" class="primary" [disabled]="busy()">Sign in</button>
        </form>
        @if (config()?.registrationEnabled) {
          <a class="link" routerLink="/register">Create a read-only account</a>
        }
      </section>
      @if (demoAccounts().length) {
        <section class="panel" aria-labelledby="demo-title">
          <h2 id="demo-title">Demo accounts</h2>
          <p class="hint">Demo mode only. Select an account to fill in the form.</p>
          <table>
            <tbody>
              @for (a of demoAccounts(); track a.username) {
                <tr>
                  <td>
                    <button type="button" class="account" [attr.data-account]="a.username" (click)="fill(a)">
                      {{ a.username }}
                    </button>
                  </td>
                  <td><span class="role">{{ a.role }}</span></td>
                  <td class="secret">{{ a.password }}</td>
                </tr>
              }
            </tbody>
          </table>
        </section>
      }
    </div>
  `,
})
export class SignInPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly params = inject(ActivatedRoute).snapshot.queryParamMap;

  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly error = signal('');
  protected readonly busy = signal(false);
  protected readonly config = this.auth.config;
  protected readonly demoAccounts = computed(() => (this.config()?.demoMode ? this.config()!.demoAccounts : []));
  protected readonly expired = this.params.get('expired') === '1';

  constructor() {
    this.auth.loadConfig().subscribe({ error: () => this.error.set('The supervisor cannot be reached.') });
  }

  protected valueOf(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected fill(account: DemoAccount): void {
    this.username.set(account.username);
    this.password.set(account.password);
    this.error.set('');
  }

  protected submit(event: Event): void {
    event.preventDefault();
    if (!this.username().trim() || !this.password()) {
      this.error.set('Enter your username and password.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.auth.signIn(this.username(), this.password()).subscribe({
      next: () => void this.router.navigateByUrl(safeReturnUrl(this.params.get('returnUrl'))),
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(e.status === 401 ? 'Wrong username or password.' : 'Sign-in failed. Try again.');
      },
    });
  }
}
