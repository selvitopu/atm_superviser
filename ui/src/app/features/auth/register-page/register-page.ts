import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { passwordProblem } from '../../../shared/password-rule';

const USERNAME = /^[a-z0-9][a-z0-9._-]{2,31}$/;
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/;

/** The server's registration rules, checked before sending so the user sees them at once; the server checks again. */
function validate(username: string, displayName: string, password: string, repeat: string): string {
  if (!USERNAME.test(username)) {
    return 'Usernames are 3 to 32 characters: lower-case letters, digits, dots, dashes and underscores, starting'
      + ' with a letter or digit';
  }
  if (!displayName || displayName.length > 64 || CONTROL.test(displayName)) {
    return 'Display names are 1 to 64 characters without control characters';
  }
  const problem = passwordProblem(username, password);
  if (problem) {
    return problem;
  }
  if (password !== repeat) {
    return 'Passwords do not match';
  }
  return '';
}

@Component({
  selector: 'app-register-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  styleUrl: '../auth-panel.css',
  template: `
    <div class="layout">
      <section class="panel" aria-labelledby="register-title">
        <p class="brand"><span class="mark" aria-hidden="true">◆</span>ATM SUPERVISOR · DEMO ACC</p>
        <p class="sub">Technical supervision</p>
        <h1 id="register-title">Create account</h1>
        <p class="hint">New accounts can view the board. An administrator can grant more rights.</p>
        @if (config()?.registrationEnabled === false) {
          <p class="notice" role="status">Registration is switched off.</p>
        }
        <form (submit)="submit($event)" novalidate>
          <label for="username">Username</label>
          <input id="username" name="username" autocomplete="username" autocapitalize="none" spellcheck="false"
            [value]="username()" (input)="username.set(valueOf($event))" />
          <label for="displayName">Display name</label>
          <input id="displayName" name="displayName" autocomplete="name"
            [value]="displayName()" (input)="displayName.set(valueOf($event))" />
          <label for="password">Password</label>
          <input id="password" name="password" type="password" autocomplete="new-password"
            [value]="password()" (input)="password.set(valueOf($event))" />
          <label for="repeat">Repeat password</label>
          <input id="repeat" name="repeat" type="password" autocomplete="new-password"
            [value]="repeat()" (input)="repeat.set(valueOf($event))" />
          <p class="error" role="alert" aria-live="assertive">{{ error() }}</p>
          <button type="submit" class="primary" [disabled]="busy() || config()?.registrationEnabled === false">
            Create account
          </button>
        </form>
        <a class="link" routerLink="/signin">Back to sign in</a>
      </section>
    </div>
  `,
})
export class RegisterPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly username = signal('');
  protected readonly displayName = signal('');
  protected readonly password = signal('');
  protected readonly repeat = signal('');
  protected readonly error = signal('');
  protected readonly busy = signal(false);
  protected readonly config = this.auth.config;

  constructor() {
    this.auth.loadConfig().subscribe({ error: () => this.error.set('The supervisor cannot be reached.') });
  }

  protected valueOf(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected submit(event: Event): void {
    event.preventDefault();
    const username = this.username().trim().toLowerCase();
    const displayName = this.displayName().trim();
    const problem = validate(username, displayName, this.password(), this.repeat());
    this.error.set(problem);
    if (problem) {
      return;
    }
    this.busy.set(true);
    this.auth.register({ username, displayName, password: this.password() })
      .pipe(switchMap(() => this.auth.signIn(username, this.password())))
      .subscribe({
        next: () => void this.router.navigateByUrl('/board'),
        error: (e: HttpErrorResponse) => {
          this.busy.set(false);
          this.error.set((e.error as { detail?: string } | null)?.detail ?? 'Registration failed. Try again.');
        },
      });
  }
}
