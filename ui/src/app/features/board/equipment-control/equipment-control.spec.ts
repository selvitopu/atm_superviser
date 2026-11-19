import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EquipmentDto } from '../../../core/api/models';
import { eq } from '../../../core/state/test-fixtures';
import { authProvider, FakeAuth, render } from '../../../testing/render';
import { EquipmentControl } from './equipment-control';

const FDPS_B = eq('FDPS-B', { type: 'FDPS', pair: 'FDPS', role: 'STANDBY' });

describe('EquipmentControl', () => {
  function setup(e: EquipmentDto, roles = ['SUPERVISOR']) {
    const f = render(EquipmentControl, { equipment: e }, [provideHttpClient(), provideHttpClientTesting(),
      authProvider(new FakeAuth(roles))]);
    const http = TestBed.inject(HttpTestingController);
    const button = (label: string) => f.all('button').find(b => b.textContent?.trim() === label) as
      HTMLButtonElement | undefined;
    const click = (label: string) => {
      button(label)!.click();
      f.fixture.detectChanges();
    };
    return { f, http, button, click };
  }

  it('aViewerSeesNoControls', () => {
    const { f } = setup(FDPS_B, ['VIEWER']);
    expect(f.all('button')).toHaveLength(0);
  });

  it('offersMaintenanceOnOrOffByTheCurrentFlag', () => {
    expect(setup(FDPS_B).button('Maintenance on')).toBeDefined();
    expect(setup(FDPS_B).button('Maintenance off')).toBeUndefined();
    expect(setup({ ...FDPS_B, maintenance: true }).button('Maintenance off')).toBeDefined();
  });

  it('restartsOnlyInMaintenance', () => {
    expect(setup(FDPS_B).button('Restart')!.disabled).toBe(true);
    expect(setup({ ...FDPS_B, maintenance: true }).button('Restart')!.disabled).toBe(false);
  });

  it('makesActiveOnlyAStandbyPairMember', () => {
    expect(setup(FDPS_B).button('Make active')).toBeDefined();
    expect(setup({ ...FDPS_B, role: 'ACTIVE' }).button('Make active')).toBeUndefined();
    expect(setup(eq('RAD-N', { pair: null, role: 'MAIN' })).button('Make active')).toBeUndefined();
  });

  it('makesActiveOnlyAnOperationalStandbyOutOfMaintenance', () => {
    const why = 'Make active needs a standby unit that is operational and not in maintenance';
    expect(setup(FDPS_B).button('Make active')!.disabled).toBe(false);
    for (const unit of [{ ...FDPS_B, health: 'FAILED' as const }, { ...FDPS_B, health: 'UNKNOWN' as const },
      { ...FDPS_B, maintenance: true }]) {
      const makeActive = setup(unit).button('Make active')!;
      expect(makeActive.disabled).toBe(true);
      expect(makeActive.title).toBe(why);
    }
  });

  it('restartsOnlyInMaintenanceAndNotWhileARestartIsInProgress', () => {
    expect(setup(FDPS_B).button('Restart')!.title).toBe('Restart needs maintenance mode');
    const restarting = setup({ ...FDPS_B, maintenance: true, values: { lastCommandResult: 'IN_PROGRESS' } })
      .button('Restart')!;
    expect(restarting.disabled).toBe(true);
    expect(restarting.title).toBe('A restart is in progress');
    const done = setup({ ...FDPS_B, maintenance: true, values: { lastCommandResult: 'SUCCESS' } }).button('Restart')!;
    expect(done.disabled).toBe(false);
  });

  it('armingAsksToConfirmNamingTheUnitAndSendsNothing', () => {
    const { f, http, click } = setup(FDPS_B);
    click('Maintenance on');
    expect(f.text()).toContain('Put FDPS-B into maintenance?');
    http.expectNone(r => r.url.endsWith('/commands')); // the impact read is allowed; no command is sent

    click('Cancel');
    expect(f.text()).not.toContain('Put FDPS-B into maintenance?');
  });

  it('confirmSendsOnceAndWaitsForTheAnswer', () => {
    const { f, http, click, button } = setup(FDPS_B);
    click('Make active');
    expect(f.text()).toContain('Make FDPS-B the active unit?');
    click('Confirm');

    const req = http.expectOne('/api/v1/equipment/FDPS-B/commands');
    expect(req.request.body).toEqual({ command: 'MAKE_ACTIVE' });
    expect(button('Confirm')).toBeUndefined(); // no second SET while the first is pending
    expect(button('Maintenance on')!.disabled).toBe(true);

    expect(f.text()).toContain('Sending Make active to FDPS-B…');

    req.flush({ equipmentId: 'FDPS-B', command: 'MAKE_ACTIVE', outcome: 'DONE' });
    f.fixture.detectChanges();
    expect(f.text()).toContain('FDPS-B: Make active done');
    expect(button('Maintenance on')!.disabled).toBe(false);
  });

  it('aRefusalShowsTheServerDetail', () => {
    const { f, http, click } = setup({ ...FDPS_B, maintenance: true });
    click('Restart');
    expect(f.text()).toContain('Restart FDPS-B?');
    click('Confirm');
    http.expectOne('/api/v1/equipment/FDPS-B/commands').flush(
      { detail: 'The equipment refused the command: restart needs maintenance mode, and no restart in progress' },
      { status: 409, statusText: 'Conflict' });
    f.fixture.detectChanges();
    expect(f.text()).toContain('FDPS-B: The equipment refused the command: restart needs maintenance mode');
  });

  it('anotherUnitClearsTheConfirmation', () => {
    const { f, click } = setup(FDPS_B);
    click('Maintenance on');
    f.fixture.componentRef.setInput('equipment', eq('SDPS-B', { type: 'SDPS', pair: 'SDPS', role: 'STANDBY' }));
    f.fixture.detectChanges();
    expect(f.text()).not.toContain('Put FDPS-B into maintenance?');
  });

  it('aResultThatArrivesAfterAnotherUnitIsSelectedNamesItsUnit', () => {
    const { f, http, click } = setup(FDPS_B);
    click('Maintenance on');
    click('Confirm');
    f.fixture.componentRef.setInput('equipment', eq('SDPS-B', { type: 'SDPS', pair: 'SDPS', role: 'STANDBY' }));
    f.fixture.detectChanges();

    http.expectOne('/api/v1/equipment/FDPS-B/commands').flush(
      { equipmentId: 'FDPS-B', command: 'MAINTENANCE_ON', outcome: 'DONE' });
    f.fixture.detectChanges();
    expect(f.text()).toContain('FDPS-B: Maintenance on done');
  });

  it('aConfirmationIsDroppedOnceTheUnitNoLongerAllowsIt', () => {
    const { f, click } = setup(FDPS_B);
    const update = (e: EquipmentDto) => {
      f.fixture.componentRef.setInput('equipment', e);
      f.fixture.detectChanges();
    };
    click('Make active');
    update({ ...FDPS_B, values: { flightPlans: 12 } }); // a fresh sample: the command still fits
    expect(f.text()).toContain('Make FDPS-B the active unit?');

    update({ ...FDPS_B, health: 'FAILED' });
    expect(f.text()).not.toContain('Make FDPS-B the active unit?');
    update(FDPS_B); // the unit recovers: nothing comes back armed
    expect(f.text()).not.toContain('Make FDPS-B the active unit?');

    click('Maintenance on');
    update({ ...FDPS_B, maintenance: true }); // another supervisor did it first
    expect(f.text()).not.toContain('Put FDPS-B into maintenance?');
  });

  it('theConfirmBarTakesFocusAndIsAnnounced', async () => {
    const { f, click, button } = setup(FDPS_B);
    click('Maintenance on');
    await f.fixture.whenStable();

    const confirm = button('Confirm')!;
    expect(document.activeElement).toBe(confirm);
    const question = f.el.querySelector('#' + confirm.getAttribute('aria-describedby'));
    expect(question?.textContent).toBe('Put FDPS-B into maintenance?');
    const bar = f.el.querySelector('.confirm')!;
    expect(bar.getAttribute('aria-labelledby')).toBe(question!.id);
    expect(bar.getAttribute('aria-live')).toBe('polite');
  });

  describe('impact preview', () => {
    const plan = (over: object = {}) => ({
      asOf: 'now', basis: 'b', singlePoints: [], outOfService: [], unknownUnits: [],
      plan: {
        units: ['FDPS-B'], verdict: 'REDUCED_MARGIN', unconfirmed: false, notes: [],
        services: [
          { id: 'SURV', verdict: 'NO_SERVICE_IMPACT', reasonsAfter: [] },
          { id: 'FD', verdict: 'REDUCED_MARGIN', reasonsAfter: ['Only one FDPS unit available'] },
        ], ...over,
      },
    });
    const impactUrl = (r: { url: string; params: { getAll(k: string): string[] | null } }) =>
      r.url === '/api/v1/impact' && r.params.getAll('maintenance')?.[0] === 'FDPS-B';

    it('maintenanceOnShowsTheImpactPreview', () => {
      const { f, http, click } = setup(FDPS_B);
      click('Maintenance on');
      expect(f.text()).toContain('Impact: assessing…');
      http.expectOne(impactUrl).flush(plan());
      f.fixture.detectChanges();
      expect(f.text()).toContain('Impact: REDUCED MARGIN — FD: Only one FDPS unit available');
      expect(f.el.querySelector('.confirm')!.textContent).toContain('Impact: REDUCED MARGIN');
    });

    it('confirmStaysEnabledWhileTheImpactLoads', () => {
      const { http, click, button } = setup(FDPS_B);
      click('Maintenance on');
      expect(button('Confirm')!.disabled).toBe(false);
      http.expectOne(impactUrl);
    });

    it('aFailedPreviewSaysSoAndConfirmStillWorks', () => {
      const { f, http, click, button } = setup(FDPS_B);
      click('Maintenance on');
      http.expectOne(impactUrl).flush({}, { status: 500, statusText: 'Server Error' });
      f.fixture.detectChanges();
      expect(f.text()).toContain('Impact could not be assessed');
      expect(button('Confirm')!.disabled).toBe(false);
      click('Confirm');
      http.expectOne('/api/v1/equipment/FDPS-B/commands');
    });

    it('showsNotesAndUnconfirmed', () => {
      const { f, http, click } = setup(FDPS_B);
      click('Maintenance on');
      http.expectOne(impactUrl).flush(plan({ notes: ['A note'], unconfirmed: true }));
      f.fixture.detectChanges();
      expect(f.text()).toContain('Impact: REDUCED MARGIN — FD: Only one FDPS unit available · A note (unconfirmed)');
    });

    it('aPreviewForAnotherUnitIsDropped', () => {
      const { f, http, click } = setup(FDPS_B);
      click('Maintenance on');
      const stale = http.expectOne(impactUrl);
      f.fixture.componentRef.setInput('equipment', eq('FDPS-A', { type: 'FDPS', pair: 'FDPS', role: 'STANDBY' }));
      f.fixture.detectChanges();
      click('Maintenance on');
      stale.flush(plan());
      f.fixture.detectChanges();
      expect(f.text()).toContain('Impact: assessing…');
      expect(f.text()).not.toContain('REDUCED MARGIN');
      http.expectOne(r => r.url === '/api/v1/impact' && r.params.getAll('maintenance')?.[0] === 'FDPS-A');
    });

    it('aPreviewAfterCancelIsDropped', () => {
      const { f, http, click } = setup(FDPS_B);
      click('Maintenance on');
      const stale = http.expectOne(impactUrl);
      click('Cancel');
      click('Maintenance on');
      stale.flush(plan());
      f.fixture.detectChanges();
      expect(f.text()).toContain('Impact: assessing…');
      http.expectOne(impactUrl);
    });

    it('otherCommandsHaveNoPreview', () => {
      const a = setup(FDPS_B);
      a.click('Make active');
      a.http.expectNone('/api/v1/impact');
      const b = setup({ ...FDPS_B, maintenance: true });
      b.click('Restart');
      b.http.expectNone(r => r.url === '/api/v1/impact');
      expect(b.f.text()).not.toContain('Impact');
    });
  });
});
