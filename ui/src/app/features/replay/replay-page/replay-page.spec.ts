import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { ReplayBoardDto, TimelineDto } from '../../../core/api/models';
import { alarm, board, eq, svc } from '../../../core/state/test-fixtures';
import { FakeAuth, authProvider } from '../../../testing/render';
import { ReplayPage } from './replay-page';

const FROM = '2026-11-20T14:00:00.000Z';
const TO = '2026-11-20T15:00:00.000Z';
const empty: TimelineDto = { markers: [], truncated: false, floods: [], gaps: [], restarts: [] };

function replayBoard(over: Partial<ReplayBoardDto> = {}): ReplayBoardDto {
  return {
    at: FROM, noData: false, gapThresholdSeconds: 90, lastRecordedAt: null, lastRecordedType: null,
    board: board({
      equipment: [eq('RAD-N', { sampledAt: '2026-11-20T14:00:00Z', values: { plotsPerScan: 12 } })],
      services: [svc('SURV', 'OK')],
      alarms: [alarm('a1', { severity: 'CRITICAL' }), alarm('a2', { severity: 'MINOR' })],
    }), ...over,
  };
}

async function open(url: string, roles = ['SUPERVISOR']) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), authProvider(new FakeAuth(roles)),
      provideRouter([{ path: 'replay', component: ReplayPage }])],
  });
  const http = TestBed.inject(HttpTestingController);
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url, ReplayPage);
  const el = harness.routeNativeElement as HTMLElement;
  const settle = () => { harness.detectChanges(); };
  return { http, harness, el, settle };
}

async function ready(url = `/replay?from=${FROM}&to=${TO}&at=${FROM}`, board = replayBoard()) {
  const t = await open(url);
  t.http.expectOne(r => r.url === '/api/v1/history/board').flush(board);
  t.http.expectOne(r => r.url === '/api/v1/history/timeline').flush(empty);
  t.http.match(r => r.url === '/api/v1/history/events').forEach(r => r.flush({ events: [], more: false }));
  t.settle();
  t.http.verify(); // any other request, such as a live one, fails the spec
  return t;
}

describe('ReplayPage', () => {
  it('rendersNoAppHeaderAndNoControlLinks', async () => {
    const { el } = await ready();
    expect(el.querySelector('app-app-header')).toBeNull();
    expect(el.querySelector('app-data-not-live-overlay')).toBeNull();
    const hrefs = Array.from(el.querySelectorAll('a')).map(a => a.getAttribute('href') ?? '');
    expect(hrefs.filter(h => !h.startsWith('/replay') && !h.startsWith('/api/v1/history/export') && h !== '/board'))
      .toEqual([]);
    expect(hrefs.some(h => /simulator|impact|admin/.test(h))).toBe(false);
    expect(el.textContent).toContain('NOT LIVE');
  });

  it('theAlarmListIsReadOnly', async () => {
    const { el } = await ready();
    expect(el.querySelectorAll('app-alarm-list tbody tr').length).toBe(2);
    expect(el.querySelector('app-alarm-list input.select')).toBeNull();
    expect(Array.from(el.querySelectorAll('app-alarm-list button')).map(b => b.textContent)).toEqual([]);
  });

  it('theDetailPanelHasNoEquipmentControl', async () => {
    const { el, http } = await ready(`/replay?from=${FROM}&to=${TO}&at=${FROM}&unit=RAD-N`);
    const panel = el.querySelector('app-detail-panel') as HTMLElement;
    expect(panel.textContent).toContain('RAD-N');
    expect(panel.querySelector('app-equipment-control')).toBeNull();
    expect(panel.textContent).not.toContain('Recent events');
    expect(panel.textContent).toContain('sampled 14:00:00Z');
    http.expectNone(r => r.url.startsWith('/api/v1/equipment'));
  });

  it('tilesLinkWithinReplay', async () => {
    const { el } = await ready();
    const tile = el.querySelector('app-equipment-tile a') as HTMLAnchorElement;
    expect(tile.getAttribute('href')).toContain('/replay?');
    expect(tile.getAttribute('href')).toContain('unit=RAD-N');
    expect(tile.getAttribute('href')).toContain('from=');
    expect(tile.textContent).toContain('sampled 14:00:00Z');
    expect(tile.textContent).not.toContain(' s ago');
  });

  it('theGapOverlayShowsTheExactCopy', async () => {
    const { el } = await ready(undefined, replayBoard({ noData: true }));
    expect(el.querySelector('main .gap')?.textContent?.trim()).toBe('No recorded data: supervisor not running');
    const clear = await ready();
    expect(clear.el.querySelector('main .gap')).toBeNull();
  });

  it('anAlarmParameterOpensTheWindowAroundTheStoredRaise', async () => {
    const t = await open('/replay?alarm=abc');
    t.http.expectOne('/api/v1/history/alarms/abc/raised').flush({ occurredAt: '2026-11-20T14:00:00Z' });
    const b = t.http.expectOne(r => r.url === '/api/v1/history/board');
    expect(b.request.params.get('at')).toBe('2026-11-20T13:59:00.000Z');
    b.flush(replayBoard());
    const tl = t.http.expectOne(r => r.url === '/api/v1/history/timeline');
    expect(tl.request.params.get('from')).toBe('2026-11-20T13:45:00.000Z');
    expect(tl.request.params.get('to')).toBe('2026-11-20T14:45:00.000Z');
    tl.flush(empty);
    t.http.match(r => r.url === '/api/v1/history/events').forEach(r => r.flush({ events: [], more: false }));
  });

  it('exportLinksCarryTheWindow', async () => {
    const { el } = await ready();
    expect(el.querySelector('summary')?.textContent?.trim()).toBe('Export incident report');
    const links = Array.from(el.querySelectorAll('.export a')) as HTMLAnchorElement[];
    expect(links.map(a => a.textContent?.trim())).toEqual(['CSV', 'JSON']);
    const base = `/api/v1/history/export?from=${encodeURIComponent(FROM)}&to=${encodeURIComponent(TO)}&format=`;
    expect(links.map(a => a.getAttribute('href'))).toEqual([base + 'csv', base + 'json']);
  });
});
