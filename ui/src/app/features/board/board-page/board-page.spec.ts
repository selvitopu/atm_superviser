import { signal } from '@angular/core';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { BoardApi } from '../../../core/api/board-api';
import { board, eq, svc } from '../../../core/state/test-fixtures';
import { FakeStore, clockProvider, render, storeProviders } from '../../../testing/render';
import { BoardPage } from './board-page';

describe('BoardPage', () => {
  function setup() {
    const store = new FakeStore();
    store.board.set(board({ services: [svc('SURV', 'OK')], equipment: [eq('RAD-N', { pair: null })] }));
    const f = render(BoardPage, {}, [
      provideRouter([]), ...storeProviders(store), clockProvider('2026-11-13T14:00:00Z'),
      { provide: BoardApi, useValue: { equipment: () => of({ equipment: eq('RAD-N'), recentEvents: [] }) } },
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ equipmentId: 'RAD-N' })), snapshot: {} } },
    ]);
    return { store, f };
  }

  it('starts the store on init and stops it on destroy', () => {
    const { store, f } = setup();
    expect(store.started).toBe(1);
    f.fixture.destroy();
    expect(store.stopped).toBe(1);
  });

  it('composes header, services, equipment, detail and alarms', () => {
    const { f } = setup();
    for (const sel of ['app-app-header', 'app-service-strip', 'app-equipment-board', 'app-detail-panel', 'app-alarm-list', 'app-data-not-live-overlay']) {
      expect(f.el.querySelector(sel), sel).not.toBeNull();
    }
  });
});
