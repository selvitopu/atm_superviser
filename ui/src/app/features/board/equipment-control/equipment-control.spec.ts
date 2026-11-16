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

  it('armingAsksToConfirmNamingTheUnitAndSendsNothing', () => {
    const { f, http, click } = setup(FDPS_B);
    click('Maintenance on');
    expect(f.text()).toContain('Put FDPS-B into maintenance?');
    http.expectNone(() => true);

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

    req.flush({ equipmentId: 'FDPS-B', command: 'MAKE_ACTIVE', outcome: 'DONE' });
    f.fixture.detectChanges();
    expect(f.text()).toContain('Make active: done');
    expect(button('Maintenance on')!.disabled).toBe(false);
  });

  it('aRefusalShowsTheServerDetail', () => {
    const { f, http, click } = setup({ ...FDPS_B, maintenance: true });
    click('Restart');
    expect(f.text()).toContain('Restart FDPS-B?');
    click('Confirm');
    http.expectOne('/api/v1/equipment/FDPS-B/commands').flush(
      { detail: 'The equipment refused the command: restart needs maintenance mode, and make active needs a standby unit' },
      { status: 409, statusText: 'Conflict' });
    f.fixture.detectChanges();
    expect(f.text()).toContain('The equipment refused the command');
  });

  it('anotherUnitClearsTheConfirmation', () => {
    const { f, click } = setup(FDPS_B);
    click('Maintenance on');
    f.fixture.componentRef.setInput('equipment', eq('SDPS-B', { type: 'SDPS', pair: 'SDPS', role: 'STANDBY' }));
    f.fixture.detectChanges();
    expect(f.text()).not.toContain('Put FDPS-B into maintenance?');
  });
});
