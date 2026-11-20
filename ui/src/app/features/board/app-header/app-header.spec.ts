import { Router, provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { authProvider, clockProvider, FakeAuth, FakeStore, render, storeProviders } from '../../../testing/render';
import { board } from '../../../core/state/test-fixtures';
import { AppHeader } from './app-header';

describe('AppHeader', () => {
  function setup(connection: 'LIVE' | 'RECONNECTING' | 'OFFLINE', auth = new FakeAuth(), live = true) {
    const store = new FakeStore();
    store.connection.set(connection);
    return render(AppHeader, { live }, [...storeProviders(store), clockProvider('2026-11-13T14:05:09Z'), authProvider(auth),
      provideRouter([])]);
  }

  it('shows the title and the UTC server clock', () => {
    const f = setup('LIVE');
    expect(f.text()).toContain('ATM SUPERVISOR · DEMO ACC');
    expect(f.text()).toContain('14:05:09Z');
  });

  it('shows the connection indicator for each state', () => {
    const cases: [ 'LIVE' | 'RECONNECTING' | 'OFFLINE', string, string][] = [
      ['LIVE', '● LIVE', 'ok'], ['RECONNECTING', '◌ RECONNECTING', 'degraded'], ['OFFLINE', '✕ OFFLINE', 'failed'],
    ];
    for (const [state, label, cls] of cases) {
      const ind = setup(state).el.querySelector('.connection') as HTMLElement;
      expect(ind.textContent?.trim()).toBe(label);
      expect(ind.classList).toContain(cls);
    }
  });

  it('showsTheUserAndRole', () => {
    const f = setup('LIVE', new FakeAuth(['ADMIN'], 'Admin One', 'admin1'));
    expect(f.el.querySelector('.user')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Admin One · ADMIN');
  });

  it('signOutReturnsToSignIn', async () => {
    const auth = new FakeAuth();
    const f = setup('LIVE', auth);
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    (f.el.querySelector('button.sign-out') as HTMLButtonElement).click();
    expect(auth.signedOut).toBe(1);
    expect(navigate).toHaveBeenCalledWith(['/signin']);
  });
  it('hidesTheLiveStateAwayFromTheBoard', () => {
    const f = setup('LIVE', new FakeAuth(), false);
    expect(f.el.querySelector('.connection')).toBeNull();
    expect(f.el.querySelector('.clock')).toBeNull();
    expect(f.text()).toContain('Supervisor One');
  });

  it('showsTheFloodChipOnTheLiveBoard', () => {
    const store = new FakeStore();
    store.board.set(board({ flood: { active: true, since: '2026-11-13T14:07:30Z' } }));
    const f = render(AppHeader, { live: true }, [...storeProviders(store), clockProvider('2026-11-13T14:05:09Z'),
      authProvider(new FakeAuth()), provideRouter([])]);
    const chip = f.el.querySelector('.flood') as HTMLElement;
    expect(chip.textContent?.trim()).toBe('ALARM FLOOD');
    expect(chip.getAttribute('aria-label')).toBe('Alarm flood since 14:07:30Z');
  });

  it('showsNoFloodChipOffTheBoard', () => {
    const store = new FakeStore();
    store.board.set(board({ flood: { active: true, since: '2026-11-13T14:07:30Z' } }));
    const f = render(AppHeader, { live: false }, [...storeProviders(store), clockProvider('2026-11-13T14:05:09Z'),
      authProvider(new FakeAuth()), provideRouter([])]);
    expect(f.el.querySelector('.flood')).toBeNull();
  });

  it('aViewerSeesImpact', () => {
    const f = setup('LIVE', new FakeAuth(['VIEWER'], 'Demo One', 'demo1'));
    expect(f.all('nav a').map(a => [a.textContent?.trim(), a.getAttribute('href')]))
      .toEqual([['Board', '/board'], ['Alarm history', '/history'], ['Impact', '/impact']]);
  });
  it('aSupervisorSeesTheSimulatorOnlyInDemoMode', () => {
    const demo = { demoMode: true, registrationEnabled: true, demoAccounts: [] };
    const supervisor = new FakeAuth(['SUPERVISOR']);
    supervisor.config.set(demo);
    expect(setup('LIVE', supervisor).all('nav a').map(a => a.getAttribute('href'))).toContain('/simulator');

    const viewer = new FakeAuth(['VIEWER'], 'Demo One', 'demo1');
    viewer.config.set(demo);
    expect(setup('LIVE', viewer).all('nav a').map(a => a.getAttribute('href'))).not.toContain('/simulator');

    const live = new FakeAuth(['SUPERVISOR']);
    live.config.set({ ...demo, demoMode: false });
    expect(setup('LIVE', live).all('nav a').map(a => a.getAttribute('href'))).not.toContain('/simulator');
  });
  it('anAdminSeesTheUsersScreen', () => {
    const admin = setup('LIVE', new FakeAuth(['ADMIN'], 'Admin One', 'admin1'));
    expect(admin.all('nav a').map(a => a.getAttribute('href'))).toContain('/admin/users');
    expect(setup('LIVE', new FakeAuth(['SUPERVISOR'])).all('nav a').map(a => a.getAttribute('href')))
      .not.toContain('/admin/users');
  });
});
