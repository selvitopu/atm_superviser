import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { ImpactDto, ServiceChangeDto } from '../../../core/api/models';
import { board, eq, svc } from '../../../core/state/test-fixtures';
import { authProvider, clockProvider, FakeAuth, FakeStore, storeProviders } from '../../../testing/render';
import { ImpactPage } from './impact-page';

const BASIS = 'Derived from the supervised equipment and the service rules; not a safety assessment';

function change(id: string, over: Partial<ServiceChangeDto> = {}): ServiceChangeDto {
  return {
    id, name: id, verdict: 'NO_SERVICE_IMPACT', before: 'OK', beforeBestCase: null, after: 'OK', afterBestCase: null,
    redundancyLostAfter: false, reasonsAfter: [], ...over,
  };
}

function report(over: Partial<ImpactDto> = {}): ImpactDto {
  return {
    asOf: '2026-11-19T10:15:02Z', basis: BASIS,
    plan: { units: [], verdict: 'NO_SERVICE_IMPACT', unconfirmed: false, services: [change('SURV'), change('FD')], notes: [] },
    singlePoints: [], outOfService: [], unknownUnits: [], ...over,
  };
}

describe('ImpactPage', () => {
  beforeEach(() => vi.useFakeTimers({ now: Date.parse('2026-11-19T10:15:00Z'), toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout', 'Date'] }));
  afterEach(() => vi.useRealTimers());

  async function setup(url = '/impact') {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(),
        provideRouter([{ path: 'impact', component: ImpactPage }]), authProvider(new FakeAuth(['VIEWER'])),
        ...storeProviders(new FakeStore()), clockProvider('2026-11-19T10:15:00Z')],
    });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url, ImpactPage);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/board').flush(board({
      equipment: [eq('RAD-N'), eq('FDPS-A', { group: 'FLIGHT_DATA' }), eq('FDPS-B', { group: 'FLIGHT_DATA' })],
      services: [svc('SURV', 'OK'), svc('FD', 'OK')],
    }));
    const el = harness.routeNativeElement as HTMLElement;
    const text = () => (el.textContent ?? '').replace(/\s+/g, ' ').trim();
    const impact = () => http.expectOne(r => r.url === '/api/v1/impact');
    const refresh = () => harness.detectChanges();
    const box = (id: string) => el.querySelector<HTMLInputElement>(`input[type=checkbox][value="${id}"]`)!;
    return { harness, http, el, text, impact, refresh, box };
  }

  it('showsThePlanVerdictAndEachServiceChange', async () => {
    const { impact, refresh, text, el } = await setup('/impact?maintenance=FDPS-B');
    const req = impact();
    expect(req.request.params.getAll('maintenance')).toEqual(['FDPS-B']);
    req.flush(report({
      plan: {
        units: ['FDPS-B'], verdict: 'REDUCED_MARGIN', unconfirmed: false, notes: ['FDPS-B is the standby of its pair.'],
        services: [change('SURV'), change('FD', { verdict: 'REDUCED_MARGIN', after: 'DEGRADED', redundancyLostAfter: true,
          reasonsAfter: ['Only one FDPS unit available'] })],
      },
    }));
    refresh();
    expect(el.querySelector('.plan .verdict')?.textContent).toContain('REDUCED MARGIN');
    expect(text()).toContain('SURV OK → OK');
    expect(text()).toContain('FD OK → DEGRADED · redundancy lost · Only one FDPS unit available');
    expect(text()).toContain('Note: FDPS-B is the standby of its pair.');
    expect(text()).toContain('as of 10:15:02 UTC · refreshes every 5 s');
    expect(text()).toContain('Single points of failure (with the plan applied)');
    expect(text()).toContain(BASIS);
  });

  it('saysSoForAnEmptyPlan', async () => {
    const { impact, refresh, text } = await setup();
    impact().flush(report());
    refresh();
    expect(text()).toContain('No maintenance planned');
    expect(text()).toContain('Single points of failure');
    expect(text()).not.toContain('with the plan applied');
  });

  it('tickingAUnitPutsItInTheUrl', async () => {
    const { impact, refresh, box, http } = await setup('/impact?maintenance=RAD-N');
    impact().flush(report());
    refresh();
    box('FDPS-A').click();
    await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/impact?maintenance=RAD-N&maintenance=FDPS-A'));
    const req = http.expectOne(r => r.url === '/api/v1/impact');
    expect(req.request.params.getAll('maintenance')).toEqual(['RAD-N', 'FDPS-A']);
  });

  it('clearRemovesThePlan', async () => {
    const { impact, refresh, el, http } = await setup('/impact?maintenance=RAD-N');
    impact().flush(report());
    refresh();
    (Array.from(el.querySelectorAll('button')).find(b => b.textContent?.trim() === 'Clear') as HTMLButtonElement).click();
    await vi.waitFor(() => expect(TestBed.inject(Router).url).toBe('/impact'));
    expect(http.expectOne(r => r.url === '/api/v1/impact').request.params.keys()).toEqual([]);
  });

  it('unitsAlreadyOutAreCheckedAndDisabled', async () => {
    const { impact, refresh, box, text } = await setup();
    impact().flush(report({ outOfService: [{ unit: 'RAD-N', reason: 'FAILED' }, { unit: 'FDPS-A', reason: 'MAINTENANCE' }] }));
    refresh();
    for (const id of ['RAD-N', 'FDPS-A']) {
      expect(box(id).checked).toBe(true);
      expect(box(id).disabled).toBe(true);
    }
    expect(box('FDPS-B').checked).toBe(false);
    expect(box('FDPS-B').disabled).toBe(false);
    expect(text()).toContain('RAD-N failed');
    expect(text()).toContain('FDPS-A maintenance');
  });

  it('listsSinglePointsWorstFirstThenOutOfService', async () => {
    const { impact, refresh, el } = await setup('/impact?maintenance=FDPS-B');
    impact().flush(report({
      singlePoints: [
        { unit: 'FDPS-A', health: 'OPERATIONAL', verdict: 'SERVICE_LOSS', unconfirmed: false,
          changes: [change('FD', { verdict: 'SERVICE_LOSS', before: 'DEGRADED', after: 'FAILED', reasonsAfter: ['No FDPS unit'] })] },
        { unit: 'RAD-N', health: 'OPERATIONAL', verdict: 'NO_SERVICE_IMPACT', unconfirmed: false, changes: [] },
      ],
      outOfService: [{ unit: 'FDPS-B', reason: 'PLANNED' }],
    }));
    refresh();
    const rows = Array.from(el.querySelectorAll('tbody tr')).map(r =>
      Array.from(r.querySelectorAll('td')).map(c => (c.textContent ?? '').replace(/\s+/g, ' ').trim()));
    expect(rows).toEqual([
      ['FDPS-A', 'OPERATIONAL', '✕ SERVICE LOSS', 'FD DEGRADED → FAILED: No FDPS unit'],
      ['RAD-N', 'OPERATIONAL', 'NO SERVICE IMPACT', '—'],
      ['FDPS-B', '—', 'Out of service', 'planned'],
    ]);
    expect(el.querySelector('caption')?.textContent).toContain('Single points of failure');
    expect(Array.from(el.querySelectorAll('th')).map(h => h.textContent?.trim()))
      .toEqual(['Unit', 'State now', 'If it fails', 'Services']);
  });

  it('anUnconfirmedVerdictNamesTheUnknownUnits', async () => {
    const { impact, refresh, text } = await setup();
    impact().flush(report({
      plan: { units: [], verdict: 'REDUCED_MARGIN', unconfirmed: true, services: [], notes: [] },
      unknownUnits: ['SDPS-A', 'SDPS-B'],
    }));
    refresh();
    expect(text()).toContain('Unconfirmed: depends on units in UNKNOWN state: SDPS-A, SDPS-B');
  });

  it('refreshesEveryFiveSecondsAndKeepsTheLastResultOnFailure', async () => {
    const { impact, refresh, text } = await setup();
    impact().flush(report({ asOf: '2026-11-19T10:15:00Z' }));
    refresh();
    await vi.advanceTimersByTimeAsync(5000);
    impact().flush(report({ asOf: '2026-11-19T10:15:05Z' }));
    refresh();
    expect(text()).toContain('as of 10:15:05 UTC');
    expect(text()).not.toContain('Last refresh failed');

    await vi.advanceTimersByTimeAsync(5000);
    impact().flush(null, { status: 500, statusText: 'Server Error' });
    refresh();
    expect(text()).toContain('as of 10:15:05 UTC');
    expect(text()).toContain('Last refresh failed at 10:15:10 UTC');
    expect(text()).toContain('No maintenance planned');
  });

  it('onlyTheLatestRequestLands', async () => {
    const { impact, refresh, text, box, http } = await setup();
    const first = impact();
    refresh();
    box('RAD-N').click();
    await vi.waitFor(() => expect(first.cancelled).toBe(true));
    const latest = http.expectOne(r => r.url === '/api/v1/impact');
    latest.flush(report({ plan: { units: ['RAD-N'], verdict: 'SERVICE_LOSS', unconfirmed: false, services: [], notes: [] } }));
    refresh();
    expect(text()).toContain('SERVICE LOSS');
  });

  it('aPlanLinkWithAnUnknownUnitShowsTheReason', async () => {
    const { impact, refresh, text, el } = await setup('/impact?maintenance=NOPE');
    impact().flush({ detail: 'Unknown unit: NOPE' }, { status: 400, statusText: 'Bad Request' });
    refresh();
    expect(text()).toContain('Unknown unit: NOPE');
    expect(text()).not.toContain('Last refresh failed');
    expect(Array.from(el.querySelectorAll('button')).some(b => b.textContent?.trim() === 'Clear')).toBe(true);
  });

  it('neverSaysSafe', async () => {
    for (const verdict of ['NO_SERVICE_IMPACT', 'REDUCED_MARGIN', 'SERVICE_LOSS']) {
      const { impact, refresh, text } = await setup();
      impact().flush(report({
        plan: { units: [], verdict, unconfirmed: false, services: [change('SURV', { verdict })], notes: [] },
        singlePoints: [{ unit: 'RAD-N', health: 'OPERATIONAL', verdict, unconfirmed: false, changes: [] }],
      }));
      refresh();
      expect(text()).not.toMatch(/\bsafe\b/i);
      expect(text()).not.toMatch(/cleared|approved/i);
    }
  });

  it('noServiceImpactIsNotGreen', async () => {
    const { impact, refresh, el } = await setup('/impact?maintenance=FDPS-B');
    impact().flush(report({
      plan: { units: ['FDPS-B'], verdict: 'NO_SERVICE_IMPACT', unconfirmed: false, services: [], notes: [] },
      singlePoints: [{ unit: 'RAD-N', health: 'OPERATIONAL', verdict: 'NO_SERVICE_IMPACT', unconfirmed: false, changes: [] }],
    }));
    refresh();
    const verdicts = Array.from(el.querySelectorAll('.verdict')).filter(v => v.textContent?.includes('NO SERVICE IMPACT'));
    expect(verdicts.length).toBe(2);
    for (const v of verdicts) {
      expect(v.classList).not.toContain('ok');
    }
  });
});
