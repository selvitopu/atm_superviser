import { applyMessage } from '../../../core/state/apply-message';
import { alarm, board, correlatedAlarm, eq } from '../../../core/state/test-fixtures';
import { FakeAuth, FakeStore, authProvider, render, storeProviders } from '../../../testing/render';
import { AlarmList } from './alarm-list';

function setup(alarms = [
  alarm('minor', { severity: 'MINOR', raisedAt: '2026-11-13T14:02:00Z' }),
  alarm('crit', { severity: 'CRITICAL', raisedAt: '2026-11-13T14:01:00Z' }),
  alarm('major', { severity: 'MAJOR', raisedAt: '2026-11-13T14:03:00Z', state: 'ACTIVE_ACK' }),
], roles: string[] = ['SUPERVISOR']) {
  const store = new FakeStore();
  store.board.set(board({ alarms }));
  return { store, f: render(AlarmList, {}, [...storeProviders(store), authProvider(new FakeAuth(roles))]) };
}

describe('AlarmList', () => {
  it('alarm list orders by severity then time and counts unacknowledged', () => {
    const { f } = setup();
    expect(f.all('tbody tr').map(r => r.querySelector('.sev')?.textContent?.trim())).toEqual(['CRIT', 'MAJ', 'MIN']);
    expect(f.el.querySelector('h2')?.textContent).toContain('2 unacknowledged');
    expect(f.text()).toContain('ALARMS');
  });

  it('orders equal severities by time descending and shows state labels and instance name', () => {
    const { f } = setup([
      alarm('old', { raisedAt: '2026-11-13T14:00:00Z', text: 'old one' }),
      alarm('new', { raisedAt: '2026-11-13T14:09:00Z', text: 'new one', instanceName: 'ch A', state: 'CLEARED_UNACK' }),
    ]);
    const rows = f.all('tbody tr');
    expect(rows[0].textContent).toContain('new one · ch A');
    expect(rows[0].textContent).toContain('14:09:00Z');
    expect(rows[0].textContent).toContain('CLEARED-UNACK');
    expect(rows[1].textContent).toContain('UNACK');
  });

  it('ack all visible acks only unacknowledged visible rows', () => {
    const { store, f } = setup([
      alarm('a', { severity: 'CRITICAL' }),
      alarm('b', { severity: 'MAJOR', state: 'ACTIVE_ACK' }),
      alarm('c', { severity: 'MINOR', suppressed: true }),
    ]);
    f.all('button').find(b => b.textContent?.includes('Ack all visible'))!.click();
    expect(store.acked).toEqual([['a']]);
  });

  it('ack selected acks the checked rows', () => {
    const { store, f } = setup();
    const boxes = f.all('input[type=checkbox].select');
    boxes[0].click();
    f.fixture.detectChanges();
    f.all('button').find(b => b.textContent?.includes('Ack selected'))!.click();
    expect(store.acked).toEqual([['crit']]);
  });

  it('ack selected never acks a selected alarm that the suppressed filter now hides', () => {
    const { store, f } = setup([alarm('a', { severity: 'CRITICAL' }), alarm('s', { severity: 'MINOR', suppressed: true })]);
    const toggleSuppressed = () => {
      f.el.querySelector<HTMLInputElement>('input.suppressed-toggle')!.click();
      f.fixture.detectChanges();
    };
    toggleSuppressed();
    f.all('input[type=checkbox].select').forEach(box => box.click());
    f.fixture.detectChanges();
    toggleSuppressed();
    f.all('button').find(b => b.textContent?.includes('Ack selected'))!.click();
    expect(store.acked).toEqual([['a']]);
  });

  it('prunes the selection when a selected alarm leaves the list', () => {
    const { store, f } = setup();
    const ackSelected = () => f.all('button').find(b => b.textContent?.includes('Ack selected')) as HTMLButtonElement;
    f.all('input[type=checkbox].select')[0].click(); // crit
    f.fixture.detectChanges();
    expect(ackSelected().disabled).toBe(false);

    const crit = store.board()!.alarms.find(a => a.id === 'crit')!;
    store.board.update(b => ({ ...b!, alarms: b!.alarms.filter(a => a.id !== 'crit') }));
    f.fixture.detectChanges();
    expect(ackSelected().disabled).toBe(true);
    ackSelected().click();
    expect(store.acked).toEqual([]);

    store.board.update(b => ({ ...b!, alarms: [...b!.alarms, crit] })); // it comes back: not selected again
    f.fixture.detectChanges();
    expect(ackSelected().disabled).toBe(true);
    expect(f.all('input[type=checkbox].select').map(b => (b as HTMLInputElement).checked)).toEqual([false, false, false]);
  });

  it('showsTheFloodBannerWhileTheFloodIsActive', () => {
    const { store, f } = setup();
    expect(f.el.querySelector('[role=status]')).toBeNull();
    store.board.update(b => ({ ...b!, flood: { active: true, since: '2026-11-13T14:07:30Z' } }));
    f.fixture.detectChanges();
    expect(f.el.querySelector('[role=status]')?.textContent?.replace(/\s+/g, ' ').trim())
      .toBe('ALARM FLOOD since 14:07:30Z · correlated alarms are grouped under their cause');
    store.board.update(b => ({ ...b!, flood: { active: false, since: null } }));
    f.fixture.detectChanges();
    expect(f.el.querySelector('[role=status]')).toBeNull();
  });

  it('hides suppressed alarms until the toggle is on', () => {
    const { f } = setup([alarm('a'), alarm('s', { suppressed: true })]);
    expect(f.all('tbody tr').length).toBe(1);
    expect(f.text()).toContain('Show suppressed (1)');
    f.el.querySelector<HTMLInputElement>('input.suppressed-toggle')!.click();
    f.fixture.detectChanges();
    expect(f.all('tbody tr').length).toBe(2);
  });

  it('marks an alarm unconfirmed while its equipment is UNKNOWN, from the live board', () => {
    const store = new FakeStore();
    store.board.set(board({
      lastEventId: 10, equipment: [eq('RAD-N'), eq('RAD-E')],
      alarms: [alarm('n', { equipmentId: 'RAD-N' }), alarm('e', { equipmentId: 'RAD-E' })],
    }));
    const f = render(AlarmList, {}, storeProviders(store));
    expect(f.all('.unconfirmed').length).toBe(0);

    store.board.update(b => applyMessage(b!, {
      kind: 'equipment', id: 11, data: eq('RAD-N', { health: 'UNKNOWN', unknownReason: 'LOST_CONTACT' }),
    }));
    f.fixture.detectChanges();
    const marked = f.all('tbody tr').filter(r => r.querySelector('.unconfirmed'));
    expect(marked.length).toBe(1);
    expect(marked[0].textContent).toContain('RAD-N');
    expect(marked[0].querySelector('.unconfirmed')?.textContent).toContain('?');

    store.board.update(b => applyMessage(b!, { kind: 'equipment', id: 12, data: eq('RAD-N', { health: 'OPERATIONAL' }) }));
    f.fixture.detectChanges();
    expect(f.all('.unconfirmed').length).toBe(0);
  });

  it('ignores the server flag: service alarms and alarms of known equipment are never unconfirmed', () => {
    const store = new FakeStore();
    store.board.set(board({
      equipment: [eq('RAD-N')],
      alarms: [
        alarm('stale', { equipmentId: 'RAD-N', unconfirmed: true }),
        alarm('svc', { equipmentId: 'SURV', code: 903, unconfirmed: true }),
      ],
    }));
    const f = render(AlarmList, {}, storeProviders(store));
    expect(f.all('tbody tr').length).toBe(2);
    expect(f.all('.unconfirmed').length).toBe(0);
  });

  it('aViewerSeesNoAckControls', () => {
    const { f } = setup(undefined, ['VIEWER']);
    expect(f.all('input.select')).toHaveLength(0);
    expect(f.text()).not.toContain('Ack selected');
    expect(f.text()).not.toContain('Ack all visible');
  });

  it('aSupervisorSeesThem', () => {
    const { f } = setup(undefined, ['SUPERVISOR']);
    expect(f.all('input.select')).toHaveLength(3);
    expect(f.text()).toContain('Ack selected');
    expect(f.text()).toContain('Ack all visible');
  });

  describe('correlation', () => {
    const cause = (over = {}) => alarm('cause', { equipmentId: 'RAD-N', code: 101, severity: 'CRITICAL', text: 'Radar failed', ...over });
    const four = (over = {}) => ['c1', 'c2', 'c3', 'c4'].map((id, i) =>
      correlatedAlarm(id, 'cause', { severity: 'MAJOR', raisedAt: `2026-11-13T14:0${i}:00Z`, ...over }));
    const button = (f: ReturnType<typeof setup>['f']) => f.all('button.group-toggle')[0] as HTMLButtonElement;
    const expand = (f: ReturnType<typeof setup>['f']) => { button(f).click(); f.fixture.detectChanges(); };
    const ackBtn = (f: ReturnType<typeof setup>['f'], label: string) =>
      f.all('button').find(b => b.textContent?.includes(label)) as HTMLButtonElement;

    it('groupsCorrelatedAlarmsUnderTheirActiveCause', () => {
      const { f } = setup([cause(), ...four()]);
      expect(f.all('tbody tr').length).toBe(1);
      expect(button(f).textContent?.trim()).toBe('+4 correlated · 4 unack');
      expect(button(f).getAttribute('aria-expanded')).toBe('false');
      expect(f.el.querySelector('#' + button(f).getAttribute('aria-controls'))).not.toBeNull();
    });

    it('expandingShowsTheCorrelatedAlarmsWithTheirRule', () => {
      const { f } = setup([cause(), ...four()]);
      expand(f);
      const rows = f.all('tbody tr');
      expect(rows.length).toBe(5);
      const children = rows.slice(1);
      expect(children.every(r => r.textContent?.includes('↳'))).toBe(true);
      expect(children.filter(r => r.textContent?.includes('input from RAD-N')).length).toBe(4);
      expect(button(f).getAttribute('aria-expanded')).toBe('true');
      expect(button(f).textContent).toContain('+4 correlated');
    });

    it('theGroupButtonFlipsAriaExpandedAndBackWhenToggled', () => {
      const { f } = setup([cause(), ...four()]);
      expect(button(f).getAttribute('aria-expanded')).toBe('false');
      expand(f);
      expect(button(f).getAttribute('aria-expanded')).toBe('true');
      expand(f);
      expect(button(f).getAttribute('aria-expanded')).toBe('false');
      expect(button(f).textContent?.trim()).toBe('+4 correlated · 4 unack');
    });

    it('theGroupLabelIsSingularForOneCorrelatedAlarm', () => {
      const { f } = setup([cause(), ...four().slice(0, 1)]);
      const box = f.el.querySelector<HTMLInputElement>('input.select')!;
      expect(box.getAttribute('aria-label')).toBe('Select alarm RAD-N Radar failed and its 1 correlated alarm');
    });

    it('aCorrelatedAlarmOfAClearedCauseIsTopLevel', () => {
      const { f } = setup([cause({ state: 'CLEARED_UNACK' }), ...four()]);
      expect(f.all('tbody tr').length).toBe(5);
      expect(f.all('button.group-toggle').length).toBe(0);
    });

    it('aCorrelatedAlarmOfAHiddenCauseIsTopLevel', () => {
      const { f } = setup([cause({ suppressed: true }), ...four()]);
      expect(f.all('tbody tr').length).toBe(4);
      expect(f.all('button.group-toggle').length).toBe(0);
      expect(f.text()).not.toContain('↳');
    });

    it('theCollapsedRowCountsUnacknowledgedCorrelatedAlarms', () => {
      const { f } = setup([cause({ state: 'ACTIVE_ACK' }), ...four()]);
      expect(button(f).textContent?.trim()).toBe('+4 correlated · 4 unack');
      expect(f.el.querySelector('h2')?.textContent).toContain('4 unacknowledged');
    });

    it('theButtonOmitsTheUnackCountWhenAllAreAcknowledged', () => {
      const { f } = setup([cause(), ...four({ state: 'ACTIVE_ACK' })]);
      expect(button(f).textContent?.trim()).toBe('+4 correlated');
    });

    it('selectingACollapsedGroupAcknowledgesTheWholeGroup', () => {
      const { store, f } = setup([cause(), ...four()]);
      const box = f.el.querySelector<HTMLInputElement>('input.select')!;
      expect(box.getAttribute('aria-label')).toBe('Select alarm RAD-N Radar failed and its 4 correlated alarms');
      box.click();
      f.fixture.detectChanges();
      ackBtn(f, 'Ack selected').click();
      expect(store.acked).toEqual([['cause', 'c4', 'c3', 'c2', 'c1']]);
    });

    it('anExpandedGroupSelectsOneAlarmEach', () => {
      const { store, f } = setup([cause(), ...four()]);
      expand(f);
      f.all('input.select')[2].click(); // c3
      f.fixture.detectChanges();
      ackBtn(f, 'Ack selected').click();
      expect(store.acked).toEqual([['c3']]);
    });

    it('ackAllVisibleIncludesCollapsedCorrelatedAlarms', () => {
      const { store, f } = setup([cause({ state: 'ACTIVE_ACK' }), ...four()]);
      ackBtn(f, 'Ack all visible').click();
      expect(store.acked).toEqual([['c4', 'c3', 'c2', 'c1']]);
    });

    it('groupsSortByTheirWorstSeverity', () => {
      const { f } = setup([
        alarm('solo', { severity: 'MINOR', raisedAt: '2026-11-13T15:00:00Z', text: 'solo minor' }),
        alarm('cause', { severity: 'MINOR', equipmentId: 'RAD-N', raisedAt: '2026-11-13T13:00:00Z', text: 'group cause' }),
        correlatedAlarm('c1', 'cause', { severity: 'MAJOR' }),
      ]);
      // the group counts as MAJOR through its correlated alarm, so it sorts before the newer MINOR row
      expect(f.all('tbody tr').map(r => r.textContent?.includes('group cause'))).toEqual([true, false]);
    });
  });
});
