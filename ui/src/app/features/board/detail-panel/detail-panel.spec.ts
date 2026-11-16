import { Observable, Subject, of, throwError } from 'rxjs';
import { BoardApi } from '../../../core/api/board-api';
import { EquipmentDetailDto } from '../../../core/api/models';
import { alarm, board, eq } from '../../../core/state/test-fixtures';
import { FakeAuth, FakeStore, authProvider, clockProvider, render, storeProviders } from '../../../testing/render';
import { DetailPanel } from './detail-panel';

class FakeApi {
  calls: string[] = [];
  commands: string[] = [];
  command(_id: string, command: string): Observable<unknown> {
    this.commands.push(command);
    return of({ outcome: 'DONE' });
  }
  pending = new Map<string, Subject<EquipmentDetailDto>>();
  failing = false;
  equipment(id: string): Observable<EquipmentDetailDto> {
    this.calls.push(id);
    if (this.failing) {
      return throwError(() => new Error('down'));
    }
    if (this.pending.has(id)) {
      return this.pending.get(id)!;
    }
    return of({
      equipment: eq(id),
      recentEvents: [{ id: 1, occurredAt: '2026-11-13T13:58:00Z', type: 'HEALTH_CHANGED', summary: 'went FAILED' }],
    });
  }
}

const sdps = () => eq('SDPS-A', {
  type: 'SDPS', pair: 'SDPS-B', role: 'ACTIVE', name: 'Surveillance Data Processing A',
  values: { trackCount: 120, latencyMs: 35 },
  tables: { sensors: [
    { index: 1, name: 'RAD-N', inputStatus: 'RECEIVING' },
    { index: 2, name: 'RAD-E', inputStatus: 'RECEIVING' },
    { index: 3, name: 'RAD-S', inputStatus: 'LOST' },
  ] },
});

async function setup(equipment = sdps(), id: string | null = 'SDPS-A', configure: (api: FakeApi) => void = () => {}) {
  const store = new FakeStore();
  store.board.set(board({ equipment: [equipment] }));
  const api = new FakeApi();
  configure(api);
  const f = render(DetailPanel, { equipmentId: id }, [
    ...storeProviders(store), clockProvider('2026-11-13T14:00:07Z'), { provide: BoardApi, useValue: api },
  ]);
  await f.fixture.whenStable();
  f.fixture.detectChanges();
  return { store, api, f };
}

describe('DetailPanel', () => {
  it('detail panel renders sdps sensor table', async () => {
    const { f } = await setup();
    const rows = f.all('table.sensors tbody tr');
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('RAD-N');
    expect(rows[0].textContent).toContain('RECEIVING');
    expect(f.all('table.sensors th').map(h => h.textContent?.trim())).toEqual(['Sensor', 'Input']);
    expect(f.text()).toContain('Tracks');
    expect(f.text()).toContain('Latency (ms)');
  });

  it('shows name, health since, role, last poll age and recent events', async () => {
    const { f } = await setup();
    expect(f.text()).toContain('Surveillance Data Processing A');
    expect(f.text()).toContain('since 13:00:00Z');
    expect(f.text()).toContain('ACTIVE');
    expect(f.text()).toContain('Last poll 7 s ago');
    expect(f.text()).toContain('went FAILED');
    expect(f.text()).toContain('13:58:00Z');
  });

  it('renders radar and fdps values with — for missing ones', async () => {
    const radar = (await setup(eq('RAD-N', { values: { rotationPeriodMs: 4000, activeChannel: 'A' } }), 'RAD-N')).f;
    for (const label of ['Rotation (ms)', 'Plots/scan', 'Active channel', 'Channel A', 'Channel B']) {
      expect(radar.text()).toContain(label);
    }
    expect(radar.text()).toContain('4000');
    expect(radar.text()).toContain('—');
    const fdps = (await setup(eq('FDPS-A', { type: 'FDPS', values: { flightPlanCount: 9 } }), 'FDPS-A')).f;
    for (const label of ['Flight plans', 'Queue depth', 'Latency (ms)']) {
      expect(fdps.text()).toContain(label);
    }
  });

  it('refetches on health, maintenance, role or alarm count changes but not on telemetry', async () => {
    const { store, api, f } = await setup();
    expect(api.calls).toEqual(['SDPS-A']);
    const refresh = async () => { f.fixture.detectChanges(); await f.fixture.whenStable(); };

    store.board.update(b => ({ ...b!, equipment: [{ ...b!.equipment[0], values: { trackCount: 5 } }] }));
    await refresh();
    expect(api.calls.length).toBe(1);

    store.board.update(b => ({ ...b!, equipment: [{ ...b!.equipment[0], health: 'DEGRADED' }] }));
    await refresh();
    expect(api.calls.length).toBe(2);

    store.board.update(b => ({ ...b!, equipment: [{ ...b!.equipment[0], maintenance: true }] }));
    await refresh();
    expect(api.calls.length).toBe(3);

    store.board.update(b => ({ ...b!, equipment: [{ ...b!.equipment[0], role: 'STANDBY' }] }));
    await refresh();
    expect(api.calls.length).toBe(4);

    store.board.update(b => ({ ...b!, alarms: [alarm('x', { equipmentId: 'SDPS-A' })] }));
    await refresh();
    expect(api.calls.length).toBe(5);

    store.board.update(b => ({ ...b!, alarms: [alarm('x', { equipmentId: 'SDPS-A' }), alarm('y', { equipmentId: 'RAD-N' })] }));
    await refresh();
    expect(api.calls.length).toBe(5);
  });

  it('shows the reason, dims the values and heads them "Last known values" when health is UNKNOWN', async () => {
    const { f } = await setup(eq('RAD-N', {
      health: 'UNKNOWN', unknownReason: 'LOST_CONTACT', values: { plotsPerScan: 300 },
    }), 'RAD-N');
    expect(f.el.querySelector('.reason')?.textContent?.trim()).toBe('lost contact');
    const values = f.el.querySelector('.values')!;
    expect(values.querySelector('h3')?.textContent?.trim()).toBe('Last known values');
    expect(values.querySelector('.dim')?.textContent).toContain('300');
  });

  it('uses the tile labels for the other unknown reasons and dims the sensor table too', async () => {
    const labels: [string, string][] = [
      ['AWAITING_STATUS', 'awaiting status'], ['STATUS_UNAVAILABLE', 'status unavailable'],
      ['NOT_YET_POLLED', 'not yet polled'], ['SUPERVISOR_RESTARTED', 'supervisor restarted'], ['RESTARTING', 'restarting'],
    ];
    for (const [reason, label] of labels) {
      const { f } = await setup({ ...sdps(), health: 'UNKNOWN', unknownReason: reason });
      expect(f.el.querySelector('.reason')?.textContent?.trim()).toBe(label);
      expect(f.el.querySelector('.values h3')?.textContent?.trim()).toBe('Last known values');
      expect(f.el.querySelector('.values .dim table.sensors')).not.toBeNull();
    }
  });

  it('shows current values at full strength, with no reason or heading, while health is known', async () => {
    const { f } = await setup();
    expect(f.el.querySelector('.reason')).toBeNull();
    expect(f.el.querySelector('.values .dim')).toBeNull();
    expect(f.text()).not.toContain('Last known values');
    expect(f.el.querySelector('.values')?.textContent).toContain('120');
  });

  it('asks for a selection when no equipment is routed', async () => {
    const { f, api } = await setup(sdps(), null);
    expect(f.text()).toContain('Select equipment');
    expect(api.calls).toEqual([]);
  });

  it('does not show the previous equipment events while the next fetch is pending', async () => {
    const store = new FakeStore();
    store.board.set(board({ equipment: [eq('RAD-N'), eq('RAD-E')] }));
    const api = new FakeApi();
    const f = render(DetailPanel, { equipmentId: 'RAD-N' }, [
      ...storeProviders(store), clockProvider('2026-11-13T14:00:07Z'), { provide: BoardApi, useValue: api },
    ]);
    await f.fixture.whenStable();
    f.fixture.detectChanges();
    expect(f.text()).toContain('went FAILED');
    api.pending.set('RAD-E', new Subject());
    f.fixture.componentRef.setInput('equipmentId', 'RAD-E');
    f.fixture.detectChanges();
    await f.fixture.whenStable();
    f.fixture.detectChanges();
    expect(f.text()).not.toContain('went FAILED');
    expect(f.text()).toContain('Loading');
  });

  it('shows Events unavailable when the fetch fails, distinct from an empty list', async () => {
    const { f } = await setup(sdps(), 'SDPS-A', api => (api.failing = true));
    expect(f.text()).toContain('Events unavailable');
    expect(f.text()).not.toContain('none');
  });
  it('refetchesTheEventsWhenACommandIsAnswered', async () => {
    // a command changes no board state (a restart in maintenance), yet its event belongs in the list at once
    const store = new FakeStore();
    store.board.set(board({ equipment: [sdps()] }));
    const api = new FakeApi();
    const f = render(DetailPanel, { equipmentId: 'SDPS-A' }, [...storeProviders(store),
      clockProvider('2026-11-13T14:00:07Z'), { provide: BoardApi, useValue: api }, authProvider(new FakeAuth(['SUPERVISOR']))]);
    await f.fixture.whenStable();
    f.fixture.detectChanges();
    expect(api.calls).toEqual(['SDPS-A']);
    const click = (label: string) => {
      (f.all('button').find(b => b.textContent?.trim() === label) as HTMLButtonElement).click();
      f.fixture.detectChanges();
    };

    click('Maintenance on');
    click('Confirm');
    await f.fixture.whenStable();

    expect(api.commands).toEqual(['MAINTENANCE_ON']);
    expect(api.calls).toEqual(['SDPS-A', 'SDPS-A']);
  });
});
