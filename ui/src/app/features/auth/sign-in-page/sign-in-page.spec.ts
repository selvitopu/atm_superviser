import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthConfigDto } from '../../../core/api/models';
import { SignInPage } from './sign-in-page';

@Component({ template: 'board' })
class BoardStub {}

const DEMO: AuthConfigDto = {
  demoMode: true, registrationEnabled: true, demoAccounts: [
    { username: 'admin1', role: 'ADMIN', password: 'admin1-demo' },
    { username: 'supervisor1', role: 'SUPERVISOR', password: 'supervisor1-demo' },
    { username: 'demo1', role: 'VIEWER', password: 'demo1-demo' },
  ],
};

describe('SignInPage', () => {
  async function setup(url = '/signin', config: AuthConfigDto = DEMO) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'signin', component: SignInPage }, { path: 'board', component: BoardStub },
          { path: 'board/:id', component: BoardStub }]),
        provideHttpClient(), provideHttpClientTesting(),
      ],
    });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/auth/config').flush(config);
    harness.detectChanges();
    const el = harness.routeNativeElement as HTMLElement;
    const type = (selector: string, value: string) => {
      const input = el.querySelector(selector) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    const submit = () => (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    return { harness, http, el, type, submit, text: () => (el.textContent ?? '').replace(/\s+/g, ' ') };
  }

  it('signsInAndReturns', async () => {
    const { harness, http, type, submit } = await setup('/signin?returnUrl=%2Fboard%2FRAD-N');
    type('#username', 'supervisor1');
    type('#password', 'supervisor1-demo');
    submit();
    http.expectOne('/api/v1/auth/login')
      .flush({ username: 'supervisor1', displayName: 'Supervisor One', roles: ['SUPERVISOR'] });
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/board/RAD-N');
  });

  it('showsWrongCredentials', async () => {
    const { harness, http, type, submit, text } = await setup();
    type('#username', 'supervisor1');
    type('#password', 'nope');
    submit();
    http.expectOne('/api/v1/auth/login').flush({ detail: 'Wrong username or password' },
      { status: 401, statusText: 'Unauthorized' });
    harness.detectChanges();
    expect(text()).toContain('Wrong username or password.');
  });

  it('showsTheSessionEndedNotice', async () => {
    const { text } = await setup('/signin?expired=1');
    expect(text()).toContain('Your session ended. Sign in again.');
  });

  it('listsDemoAccountsInDemoModeAndFillsTheForm', async () => {
    const { harness, el, text } = await setup();
    expect(text()).toContain('Demo accounts');
    (el.querySelector('button[data-account="supervisor1"]') as HTMLButtonElement).click();
    harness.detectChanges();
    expect((el.querySelector('#username') as HTMLInputElement).value).toBe('supervisor1');
    expect((el.querySelector('#password') as HTMLInputElement).value).toBe('supervisor1-demo');
  });

  it('hidesTheRegisterLinkWhenRegistrationIsOff', async () => {
    const { text } = await setup('/signin', { demoMode: false, registrationEnabled: false, demoAccounts: [] });
    expect(text()).not.toContain('Create a read-only account');
    expect(text()).not.toContain('Demo accounts');
  });
});
