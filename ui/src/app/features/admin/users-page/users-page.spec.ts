import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AdminUserDto } from '../../../core/api/admin-api';
import { authProvider, clockProvider, FakeAuth, FakeStore, render, storeProviders } from '../../../testing/render';
import { UsersPage } from './users-page';

const USERS: AdminUserDto[] = [
  { username: 'admin1', displayName: 'Admin One', role: 'ADMIN', enabled: true },
  { username: 'demo1', displayName: 'Demo One', role: 'VIEWER', enabled: true },
  { username: 'opsviewer', displayName: 'Ops Viewer', role: 'VIEWER', enabled: false },
];

describe('UsersPage', () => {
  function setup() {
    const f = render(UsersPage, {}, [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      authProvider(new FakeAuth(['ADMIN'], 'Admin One', 'admin1')), ...storeProviders(new FakeStore()),
      clockProvider('2026-11-16T10:00:00Z')]);
    const http = TestBed.inject(HttpTestingController);
    const refresh = () => f.fixture.detectChanges();
    http.expectOne('/api/v1/admin/users').flush(USERS);
    refresh();
    const row = (username: string) =>
      f.all('tbody tr').find(r => r.querySelector('.username')?.textContent?.trim() === username)!;
    const click = (username: string | null, label: string) => {
      const scope = username ? Array.from(row(username).querySelectorAll('button')) : f.all('button');
      (scope.find(b => b.textContent?.trim() === label) as HTMLButtonElement).click();
      refresh();
    };
    return { f, http, refresh, row, click };
  }

  it('listsTheAccountsWithRoleAndState', () => {
    const { row } = setup();
    expect((row('demo1').querySelector('select.role') as HTMLSelectElement).value).toBe('VIEWER');
    expect(row('demo1').textContent).toContain('Demo One');
    expect(row('opsviewer').textContent).toContain('Disabled');
  });

  it('keepsTheirOwnRowReadOnly', () => {
    const { row } = setup();
    expect(row('admin1').querySelectorAll('select, button, input')).toHaveLength(0);
    expect(row('admin1').textContent).toContain('ADMIN');
    expect(row('admin1').textContent).toContain('you');
  });

  it('savesARoleChange', () => {
    const { f, http, refresh, row, click } = setup();
    const select = row('demo1').querySelector('select.role') as HTMLSelectElement;
    select.value = 'SUPERVISOR';
    select.dispatchEvent(new Event('change'));
    refresh();
    click('demo1', 'Save');
    const req = http.expectOne('/api/v1/admin/users/demo1');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ role: 'SUPERVISOR' });
    req.flush({ ...USERS[1], role: 'SUPERVISOR' });
    refresh();
    expect(f.text()).toContain('demo1 is now SUPERVISOR; their sessions have ended.');
  });

  it('disablingAsksForConfirmation', () => {
    const { f, http, refresh, row, click } = setup();
    click('demo1', 'Disable');
    expect(f.text()).toContain('Disable demo1? They are signed out at once.');
    http.expectNone('/api/v1/admin/users/demo1');

    click(null, 'Confirm');
    const req = http.expectOne('/api/v1/admin/users/demo1');
    expect(req.request.body).toEqual({ enabled: false });
    req.flush({ ...USERS[1], enabled: false });
    refresh();
    expect(row('demo1').textContent).toContain('Disabled');
  });

  it('enablesWithoutConfirmation', () => {
    const { http, click } = setup();
    click('opsviewer', 'Enable');
    expect(http.expectOne('/api/v1/admin/users/opsviewer').request.body).toEqual({ enabled: true });
  });

  it('setsAPasswordUnderTheRule', () => {
    const { f, http, refresh, row, click } = setup();
    click('demo1', 'Set password');
    const input = row('demo1').querySelector('input.password') as HTMLInputElement;
    input.value = 'short';
    input.dispatchEvent(new Event('input'));
    click('demo1', 'Set');
    expect(f.text()).toContain('Passwords are 10 to 72 characters, and accented letters count double');
    http.expectNone('/api/v1/admin/users/demo1/password');

    input.value = 'fresh-pass-123';
    input.dispatchEvent(new Event('input'));
    click('demo1', 'Set');
    const req = http.expectOne('/api/v1/admin/users/demo1/password');
    expect(req.request.body).toEqual({ password: 'fresh-pass-123' });
    req.flush(null, { status: 204, statusText: 'No Content' });
    refresh();
    expect(f.text()).toContain('demo1 has a new password; their sessions have ended.');
  });

  it('showsTheServerRefusal', () => {
    const { f, http, refresh, click } = setup();
    click('opsviewer', 'Enable');
    http.expectOne('/api/v1/admin/users/opsviewer').flush({ detail: 'No such user' },
      { status: 404, statusText: 'Not Found' });
    refresh();
    expect(f.text()).toContain('No such user');
  });
});
