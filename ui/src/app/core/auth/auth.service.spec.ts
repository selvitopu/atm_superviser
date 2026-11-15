import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { UserDto } from '../api/models';
import { AuthService, roleAllows } from './auth.service';

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('signInLoadsTheConfigFirstAndStoresTheUser', () => {
    let result: UserDto | undefined;
    auth.signIn('supervisor1', 'p+ss w&rd').subscribe(u => (result = u));

    http.expectOne('/api/v1/auth/config').flush({ demoMode: true, registrationEnabled: true, demoAccounts: [] });
    const login = http.expectOne('/api/v1/auth/login');
    expect(login.request.method).toBe('POST');
    expect(login.request.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
    // "+" and "&" must survive form encoding, or such a password could never sign in
    expect(login.request.body).toBe('username=supervisor1&password=p%2Bss+w%26rd');
    login.flush({ username: 'supervisor1', displayName: 'Supervisor One', roles: ['SUPERVISOR'] });

    expect(result?.username).toBe('supervisor1');
    expect(auth.user()?.roles).toEqual(['SUPERVISOR']);
  });

  it('hasRoleFollowsTheHierarchy', () => {
    expect(roleAllows(['ADMIN'], 'SUPERVISOR')).toBe(true);
    expect(roleAllows(['ADMIN'], 'VIEWER')).toBe(true);
    expect(roleAllows(['SUPERVISOR'], 'VIEWER')).toBe(true);
    expect(roleAllows(['VIEWER'], 'SUPERVISOR')).toBe(false);
    expect(roleAllows(['SUPERVISOR'], 'ADMIN')).toBe(false);
    expect(auth.hasRole('VIEWER')).toBe(false);
  });

  it('signOutForgetsTheUser', () => {
    auth.me().subscribe();
    http.expectOne('/api/v1/auth/me').flush({ username: 'demo1', displayName: 'Demo One', roles: ['VIEWER'] });
    auth.signOut().subscribe();
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    expect(auth.user()).toBeNull();
  });
});
