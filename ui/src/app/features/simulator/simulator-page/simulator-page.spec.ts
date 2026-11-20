import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { SimDeviceDto } from '../../../core/api/simulator-api';
import { authProvider, clockProvider, FakeAuth, FakeStore, render, storeProviders } from '../../../testing/render';
import { SimulatorPage } from './simulator-page';

function device(id: string, type: string, over: Partial<SimDeviceDto> = {}): SimDeviceDto {
  return { id, type, status: 'OPERATIONAL', role: 'MAIN', maintenance: false, faults: [], responding: true,
    dropTraps: false, ...over };
}

const DEVICES = [
  device('RAD-N', 'RADAR'), device('RAD-E', 'RADAR'),
  device('SDPS-A', 'SDPS', { role: 'ACTIVE', status: 'FAILED', faults: ['FAIL'] }),
  device('FDPS-A', 'FDPS', { role: 'ACTIVE' }),
];

describe('SimulatorPage', () => {
  afterEach(() => vi.useRealTimers());

  function setup() {
    vi.useFakeTimers();
    const f = render(SimulatorPage, {}, [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      authProvider(new FakeAuth(['SUPERVISOR'])), ...storeProviders(new FakeStore()),
      clockProvider('2026-11-16T10:00:00Z')]);
    const http = TestBed.inject(HttpTestingController);
    const refresh = () => f.fixture.detectChanges();
    http.expectOne('/api/v1/simulator/devices').flush(DEVICES);
    refresh();
    const row = (id: string) => f.all('tbody tr').find(r => r.querySelector('.id')?.textContent?.trim() === id)!;
    const buttons = (id: string) => Array.from(row(id).querySelectorAll('button')).map(b => b.textContent?.trim());
    const click = (id: string | null, label: string) => {
      const scope = id ? Array.from(row(id).querySelectorAll('button')) : f.all('button');
      (scope.find(b => b.textContent?.trim() === label) as HTMLButtonElement).click();
      refresh();
    };
    return { f, http, refresh, row, buttons, click };
  }

  it('notesThatAFloodShowsOnTheBoard', () => {
    const { f } = setup();
    expect(f.text()).toContain('An alarm flood shows on the board.');
  });

  it('listsTheDevicesWithTheirStateAndFaults', () => {
    const { row } = setup();
    expect(row('SDPS-A').textContent).toContain('FAILED');
    expect(row('SDPS-A').querySelector('.faults')?.textContent).toContain('FAIL');
  });

  it('offersOnlyTheFaultsEachTypeSupports', () => {
    const { buttons } = setup();
    expect(buttons('FDPS-A')).toEqual(['Fail', 'Degrade', 'Unreachable', 'Drop traps', 'Recover']);
    expect(buttons('RAD-N')).toEqual(['Fail', 'Degrade', 'Channel A fail', 'Channel B fail', 'Unreachable',
      'Drop traps', 'Recover']);
    expect(buttons('SDPS-A')).toContain('Sensor lost');
  });

  it('injectsAFaultAndShowsTheNewState', () => {
    const { http, refresh, row, click } = setup();
    click('RAD-N', 'Fail');
    const req = http.expectOne('/api/v1/simulator/devices/RAD-N/faults');
    expect(req.request.body).toEqual({ fault: 'FAIL', target: null });
    req.flush(device('RAD-N', 'RADAR', { status: 'FAILED', faults: ['FAIL'] }));
    refresh();
    expect(row('RAD-N').textContent).toContain('FAILED');
  });

  it('sensorLostNamesTheSensor', () => {
    const { f, http, row, click } = setup();
    const select = row('SDPS-A').querySelector('select.sensor') as HTMLSelectElement;
    expect(Array.from(select.options).map(o => o.value)).toEqual(['RAD-N', 'RAD-E']);
    select.value = 'RAD-E';
    select.dispatchEvent(new Event('change'));
    f.fixture.detectChanges();
    click('SDPS-A', 'Sensor lost');
    expect(http.expectOne('/api/v1/simulator/devices/SDPS-A/faults').request.body)
      .toEqual({ fault: 'SENSOR_LOST', target: 'RAD-E' });
  });

  it('resettingEverythingNeedsConfirmation', () => {
    const { f, http, click } = setup();
    click(null, 'Reset all');
    expect(f.text()).toContain('Clear every fault and restore the default roles?');
    http.expectNone('/api/v1/simulator/reset');

    click(null, 'Confirm');
    http.expectOne('/api/v1/simulator/reset').flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/v1/simulator/devices').flush(DEVICES);
  });

  it('refreshesEveryTwoSeconds', () => {
    const { http } = setup();
    vi.advanceTimersByTime(2000);
    http.expectOne('/api/v1/simulator/devices').flush(DEVICES);
  });

  it('showsTheSimulatorRefusal', () => {
    const { f, http, refresh, click } = setup();
    click('RAD-N', 'Fail');
    http.expectOne('/api/v1/simulator/devices/RAD-N/faults').flush({ detail: 'Unknown equipment RAD-N' },
      { status: 404, statusText: 'Not Found' });
    refresh();
    expect(f.text()).toContain('Unknown equipment RAD-N');
  });
});
