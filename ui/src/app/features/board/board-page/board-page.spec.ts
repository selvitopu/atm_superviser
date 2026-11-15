import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { BoardApi } from '../../../core/api/board-api';
import { board, eq, svc } from '../../../core/state/test-fixtures';
import { routes } from '../../../app.routes';
import { FakeAuth, FakeStore, authProvider, clockProvider, storeProviders } from '../../../testing/render';
import { BoardPage } from './board-page';

describe('BoardPage', () => {
  async function setup() {
    const store = new FakeStore();
    store.board.set(board({ services: [svc('SURV', 'OK')], equipment: [eq('RAD-N', { pair: null }), eq('RAD-E', { pair: null })] }));
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes), ...storeProviders(store), clockProvider('2026-11-13T14:00:00Z'), authProvider(new FakeAuth()),
        { provide: BoardApi, useValue: { equipment: (id: string) => of({ equipment: eq(id), recentEvents: [] }) } },
      ],
    });
    const harness = await RouterTestingHarness.create();
    return { store, harness };
  }

  it('starts the store on init and stops it on destroy', async () => {
    const { store, harness } = await setup();
    await harness.navigateByUrl('/board');
    expect(store.started).toBe(1);
    TestBed.resetTestingModule();
    expect(store.stopped).toBe(1);
  });

  it('composes header, services, equipment, detail, alarms and overlay', async () => {
    const { harness } = await setup();
    await harness.navigateByUrl('/board');
    const el = harness.routeNativeElement!;
    for (const sel of ['app-app-header', 'app-service-strip', 'app-equipment-board', 'app-detail-panel', 'app-alarm-list', 'app-data-not-live-overlay']) {
      expect(el.querySelector(sel), sel).not.toBeNull();
    }
  });

  it('keeps one page instance while selecting equipment', async () => {
    const { store, harness } = await setup();
    const page = await harness.navigateByUrl('/board', BoardPage);
    await harness.navigateByUrl('/board/RAD-N');
    expect(harness.routeNativeElement?.textContent).toContain('RAD-N');
    await harness.navigateByUrl('/board/RAD-E');
    harness.detectChanges();
    expect(store.started).toBe(1);
    expect(store.stopped).toBe(0);
    expect(harness.routeNativeElement?.querySelector('app-detail-panel')?.textContent).toContain('RAD-E');
    expect(page).toBeTruthy();
  });

  it('redirects the root to /board', async () => {
    const { harness } = await setup();
    await harness.navigateByUrl('/');
    expect(TestBed.inject(Router).url).toBe('/board');
  });
});
