import { applyMessage } from '../../../core/state/apply-message';
import { alarm, board, eq } from '../../../core/state/test-fixtures';
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
});
