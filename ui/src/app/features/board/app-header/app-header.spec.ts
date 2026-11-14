import { clockProvider, FakeStore, render, storeProviders } from '../../../testing/render';
import { AppHeader } from './app-header';

describe('AppHeader', () => {
  function setup(connection: 'LIVE' | 'RECONNECTING' | 'OFFLINE') {
    const store = new FakeStore();
    store.connection.set(connection);
    return render(AppHeader, {}, [...storeProviders(store), clockProvider('2026-11-13T14:05:09Z')]);
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
});
