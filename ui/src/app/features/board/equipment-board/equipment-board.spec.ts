import { provideRouter } from '@angular/router';
import { board, eq } from '../../../core/state/test-fixtures';
import { FakeStore, clockProvider, render, storeProviders } from '../../../testing/render';
import { EquipmentBoard } from './equipment-board';

describe('EquipmentBoard', () => {
  function setup() {
    const store = new FakeStore();
    store.board.set(board({
      equipment: [
        eq('FDPS-A', { group: 'FLIGHT_DATA', type: 'FDPS', pair: 'FDPS-B', role: 'ACTIVE' }),
        eq('FDPS-B', { group: 'FLIGHT_DATA', type: 'FDPS', pair: 'FDPS-A', role: 'STANDBY' }),
        eq('RAD-N', { pair: null, role: 'STANDALONE' }),
        eq('SDPS-A', { type: 'SDPS', pair: 'SDPS-B', role: 'ACTIVE' }),
        eq('SDPS-B', { type: 'SDPS', pair: 'SDPS-A', role: 'STANDBY' }),
      ],
    }));
    return render(EquipmentBoard, {}, [provideRouter([]), clockProvider('2026-11-13T14:00:00Z'), ...storeProviders(store)]);
  }

  it('shows the groups in order with their headings', () => {
    const headings = setup().all('h2').map(h => h.textContent?.trim());
    expect(headings).toEqual(['SURVEILLANCE', 'FLIGHT DATA']);
  });

  it('shows pairs once as A ⇄ B with role labels', () => {
    const f = setup();
    expect(f.all('.pair').length).toBe(2);
    expect(f.all('.pair')[0].textContent).toContain('⇄');
    expect(f.all('app-equipment-tile').length).toBe(5);
    expect(f.text()).toContain('ACTIVE');
    expect(f.text()).toContain('STANDBY');
  });
});
