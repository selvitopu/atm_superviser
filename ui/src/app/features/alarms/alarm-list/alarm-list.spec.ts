import { alarm, board } from '../../../core/state/test-fixtures';
import { FakeStore, render, storeProviders } from '../../../testing/render';
import { AlarmList } from './alarm-list';

function setup(alarms = [
  alarm('minor', { severity: 'MINOR', raisedAt: '2026-11-13T14:02:00Z' }),
  alarm('crit', { severity: 'CRITICAL', raisedAt: '2026-11-13T14:01:00Z' }),
  alarm('major', { severity: 'MAJOR', raisedAt: '2026-11-13T14:03:00Z', state: 'ACTIVE_ACK' }),
]) {
  const store = new FakeStore();
  store.board.set(board({ alarms }));
  return { store, f: render(AlarmList, {}, storeProviders(store)) };
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

  it('hides suppressed alarms until the toggle is on', () => {
    const { f } = setup([alarm('a'), alarm('s', { suppressed: true })]);
    expect(f.all('tbody tr').length).toBe(1);
    expect(f.text()).toContain('Show suppressed (1)');
    f.el.querySelector<HTMLInputElement>('input.suppressed-toggle')!.click();
    f.fixture.detectChanges();
    expect(f.all('tbody tr').length).toBe(2);
  });

  it('unconfirmed alarm shows marker', () => {
    const { f } = setup([alarm('u', { unconfirmed: true }), alarm('v')]);
    expect(f.all('.unconfirmed').length).toBe(1);
    expect(f.all('.unconfirmed')[0].textContent).toContain('?');
  });
});
