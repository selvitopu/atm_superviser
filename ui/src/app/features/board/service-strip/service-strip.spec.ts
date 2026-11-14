import { board, svc } from '../../../core/state/test-fixtures';
import { FakeStore, render, storeProviders } from '../../../testing/render';
import { ServiceStrip } from './service-strip';

describe('ServiceStrip', () => {
  it('shows a chip per service with badge and joined reasons as tooltip', () => {
    const store = new FakeStore();
    store.board.set(board({
      services: [
        svc('SURV', 'UNKNOWN', { bestCase: 'DEGRADED', reasons: ['RAD-N unknown', 'SDPS-A failed'] }),
        svc('FD', 'OK'),
      ],
    }));
    const f = render(ServiceStrip, {}, storeProviders(store));
    const chips = f.all('.chip');
    expect(chips.length).toBe(2);
    expect(chips[0].textContent).toContain('SURV');
    expect(chips[0].textContent).toContain('UNKNOWN · at best DEGRADED');
    expect(chips[0].getAttribute('title')).toBe('RAD-N unknown; SDPS-A failed');
    expect(chips[1].textContent).toContain('FD');
    expect(chips[1].querySelector('app-status-badge')?.classList).toContain('ok');
  });
});
