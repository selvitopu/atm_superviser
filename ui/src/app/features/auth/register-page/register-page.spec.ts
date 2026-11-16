import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { RegisterPage } from './register-page';

@Component({ template: 'board' })
class BoardStub {}

describe('RegisterPage', () => {
  async function setup() {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'register', component: RegisterPage }, { path: 'board', component: BoardStub }]),
        provideHttpClient(), provideHttpClientTesting(),
      ],
    });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/register');
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/config').flush({ demoMode: false, registrationEnabled: true, demoAccounts: [] });
    harness.detectChanges();
    const el = harness.routeNativeElement as HTMLElement;
    const fill = (username: string, displayName: string, password: string, repeat = password) => {
      for (const [id, value] of [['#username', username], ['#displayName', displayName], ['#password', password],
        ['#repeat', repeat]] as const) {
        const input = el.querySelector(id) as HTMLInputElement;
        input.value = value;
        input.dispatchEvent(new Event('input'));
      }
    };
    const submit = () => {
      (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
      harness.detectChanges();
    };
    return { harness, http, el, fill, submit, text: () => (el.textContent ?? '').replace(/\s+/g, ' ') };
  }

  it('validatesBeforeSending', async () => {
    const { http, fill, submit, text } = await setup();
    fill('ab', 'Ops Viewer', 'long-enough-pw');
    submit();
    expect(text()).toContain('Usernames are 3 to 32 characters');
    fill('opsviewer', 'Ops Viewer', 'long-enough-pw', 'different-pw');
    submit();
    expect(text()).toContain('Passwords do not match');
    fill('opsviewer01', 'Ops Viewer', 'OpsViewer01');
    submit();
    expect(text()).toContain('The password must not be the username');
    fill('opsviewer01', 'Ops Viewer', 'ş'.repeat(37));
    submit();
    expect(text()).toContain('Passwords are 10 to 72 characters, and accented letters count double');
    http.expectNone('/api/v1/auth/register');
  });

  it('aTakenNameShowsTheServerMessage', async () => {
    const { harness, http, fill, submit, text } = await setup();
    fill('admin1', 'Another Admin', 'long-enough-pw');
    submit();
    http.expectOne('/api/v1/auth/register').flush({ detail: 'That username is taken' },
      { status: 409, statusText: 'Conflict' });
    harness.detectChanges();
    expect(text()).toContain('That username is taken');
  });

  it('registersSignsInAndOpensTheBoard', async () => {
    const { harness, http, fill, submit } = await setup();
    fill('  OpsViewer ', 'Ops Viewer', 'long-enough-pw');
    submit();
    const register = http.expectOne('/api/v1/auth/register');
    expect(register.request.body).toEqual({ username: 'opsviewer', displayName: 'Ops Viewer', password: 'long-enough-pw' });
    register.flush({ username: 'opsviewer', displayName: 'Ops Viewer', roles: ['VIEWER'] }, { status: 201, statusText: 'Created' });
    http.expectOne('/api/v1/auth/login')
      .flush({ username: 'opsviewer', displayName: 'Ops Viewer', roles: ['VIEWER'] });
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/board');
  });
});
