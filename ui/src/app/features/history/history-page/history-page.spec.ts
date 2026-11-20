import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AlarmDto, PageDto } from '../../../core/api/models';
import { alarm, board, correlatedAlarm, eq, svc } from '../../../core/state/test-fixtures';
import { authProvider, clockProvider, FakeAuth, FakeStore, render, storeProviders } from '../../../testing/render';
import { HistoryPage } from './history-page';

function page(items: AlarmDto[], pageNo = 0, total = items.length, size = 50): PageDto<AlarmDto> {
  return { items, page: pageNo, size, total };
}

describe('HistoryPage', () => {
  function setup() {
    const f = render(HistoryPage, {}, [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      authProvider(new FakeAuth(['VIEWER'])), ...storeProviders(new FakeStore()), clockProvider('2026-11-16T10:00:00Z')]);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/board').flush(board({ equipment: [eq('RAD-N'), eq('SDPS-A')],
      services: [svc('SURV', 'OK'), svc('FD', 'OK')] }));
    const search = () => http.expectOne(r => r.url === '/api/v1/alarms');
    const refresh = () => f.fixture.detectChanges();
    const set = (selector: string, value: string) => {
      const input = f.el.querySelector(selector) as HTMLInputElement | HTMLSelectElement;
      input.value = value;
      input.dispatchEvent(new Event(input.tagName === 'SELECT' ? 'change' : 'input'));
    };
    const click = (label: string) => {
      (f.all('button').find(b => b.textContent?.trim() === label) as HTMLButtonElement).click();
      refresh();
    };
    return { f, http, search, refresh, set, click };
  }

  it('startsWithEveryAlarmNewestFirst', () => {
    const { search } = setup();
    expect(search().request.params.toString()).toBe('state=all&page=0&size=50');
  });

  it('searchesWithTheFiltersInUtc', () => {
    const { search, set, click, refresh } = setup();
    search().flush(page([]));
    refresh(); // the equipment options arrive with the board
    set('select.state', 'open');
    set('select.severity', 'CRITICAL');
    set('select.equipment', 'RAD-N');
    set('input.from', '2026-11-16T08:00');
    set('input.to', '2026-11-16T09:30');
    click('Search');
    expect(search().request.params.toString()).toBe('state=open&severity=CRITICAL&equipment=RAD-N'
      + '&from=2026-11-16T08:00:00.000Z&to=2026-11-16T09:30:00.000Z&page=0&size=50');
  });

  it('offersTheKnownEquipmentAndTheServicesWhoseAlarmsTheSupervisorRaises', () => {
    const { f, search, refresh } = setup();
    search().flush(page([]));
    refresh();
    expect(f.all('select.equipment option').map(o => o.textContent?.trim()))
      .toEqual(['Any', 'RAD-N', 'SDPS-A', 'SURV', 'FD']);
  });

  it('pagesThroughTheResults', () => {
    const { f, search, refresh, click } = setup();
    const fifty = Array.from({ length: 50 }, (_, i) => alarm('a' + i));
    search().flush(page(fifty, 0, 120));
    refresh();
    expect(f.text()).toContain('1–50 of 120');
    const previous = () => f.all('button').find(b => b.textContent?.trim() === 'Previous') as HTMLButtonElement;
    expect(previous().disabled).toBe(true);

    click('Next');
    const second = search();
    expect(second.request.params.get('page')).toBe('1');
    second.flush(page(fifty, 1, 120));
    refresh();
    expect(f.text()).toContain('51–100 of 120');
    expect(previous().disabled).toBe(false);
  });

  it('showsWhoAcknowledgedAndWhenItCleared', () => {
    const { f, search, refresh } = setup();
    search().flush(page([alarm('a', { equipmentId: 'RAD-N', text: 'Equipment failed', state: 'CLOSED',
      raisedAt: '2026-11-16T08:01:00Z', ackedBy: 'supervisor1', ackedAt: '2026-11-16T08:02:00Z',
      clearedAt: '2026-11-16T08:05:00Z' })]));
    refresh();
    const row = f.all('tbody tr')[0].textContent ?? '';
    expect(row).toContain('2026-11-16 08:01:00Z');
    expect(row).toContain('Equipment failed');
    expect(row).toContain('supervisor1 2026-11-16 08:02:00Z');
    expect(row).toContain('2026-11-16 08:05:00Z');
    expect(row).toContain('CLOSED');
  });

  it('showsTheCorrelation', () => {
    const { f, search, refresh } = setup();
    search().flush(page([correlatedAlarm('c', 'cause'), alarm('plain')]));
    refresh();
    expect(f.all('th').map(h => h.textContent)).toContain('Correlated');
    const cells = f.all('tbody tr').map(r => r.querySelector('td.correlated')?.textContent?.trim());
    expect(cells).toEqual(['↳ RAD-N 101 · input from RAD-N', '']);
  });

  it('saysSoWhenNothingMatches', () => {
    const { f, search, refresh } = setup();
    search().flush(page([]));
    refresh();
    expect(f.text()).toContain('No alarms match');
  });

  it('pagesThroughTheSearchItShowsNotTheFiltersBeingEdited', () => {
    const { f, search, refresh, click, set } = setup();
    search().flush(page(Array.from({ length: 50 }, (_, i) => alarm('a' + i)), 0, 120));
    refresh();

    set('select.severity', 'CRITICAL'); // edited, not searched yet
    click('Next');
    expect(search().request.params.toString()).toBe('state=all&page=1&size=50');
    expect(f.el.querySelector<HTMLSelectElement>('select.severity')!.value).toBe('CRITICAL');
  });

  it('saysSoWhenTheSearchFails', () => {
    const { f, search, refresh } = setup();
    search().flush(null, { status: 500, statusText: 'Server Error' });
    refresh();
    expect(f.text()).toContain('Search failed. Try again.');
  });

  it('showsTheServersReasonForARefusedSearch', () => {
    const { f, search, refresh } = setup();
    search().flush({ detail: 'Validation failure' }, { status: 400, statusText: 'Bad Request' });
    refresh();
    expect(f.text()).toContain('Validation failure');
    expect(f.text()).not.toContain('Search failed');
  });

  it('onlyTheLatestSearchLands', () => {
    const { f, http, search, refresh, set, click } = setup();
    const first = search();
    set('select.severity', 'CRITICAL');
    click('Search');

    expect(first.cancelled).toBe(true);
    const latest = http.expectOne(r => r.url === '/api/v1/alarms' && r.params.get('severity') === 'CRITICAL');
    latest.flush(page([alarm('critical', { text: 'Latest search' })]));
    refresh();
    expect(f.text()).toContain('Latest search');
  });
});
