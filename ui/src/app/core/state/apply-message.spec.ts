import { alarm, board, eq, svc } from './test-fixtures';
import { applyMessage } from './apply-message';

describe('applyMessage', () => {
  it('keeps telemetry when an equipment event arrives', () => {
    const b = board({ equipment: [eq('RAD-N', { values: { plotsPerScan: 300 } })], lastEventId: 10 });
    const out = applyMessage(b, { kind: 'equipment', id: 11, data: eq('RAD-N', { health: 'FAILED', values: {} }) });
    expect(out.equipment[0].health).toBe('FAILED');
    expect(out.equipment[0].values['plotsPerScan']).toBe(300);
    expect(out.lastEventId).toBe(11);
  });

  it('keeps the poll contact fields when an equipment event arrives', () => {
    const current = eq('RAD-N');
    current.contact.lastPollAt = '2026-11-13T14:05:00Z';
    current.contact.consecutiveFailures = 2;
    const incoming = eq('RAD-N', { health: 'DEGRADED' });
    incoming.contact.reachable = false;
    const out = applyMessage(board({ equipment: [current], lastEventId: 1 }), { kind: 'equipment', id: 2, data: incoming });
    expect(out.equipment[0].contact.reachable).toBe(false);
    expect(out.equipment[0].contact.lastPollAt).toBe('2026-11-13T14:05:00Z');
    expect(out.equipment[0].contact.consecutiveFailures).toBe(2);
  });

  it('anEquipmentMessageKeepsSampledAt', () => {
    const current = eq('RAD-N', { sampledAt: '2026-11-13T14:00:20Z' });
    const out = applyMessage(board({ equipment: [current], lastEventId: 1 }),
      { kind: 'equipment', id: 2, data: eq('RAD-N', { health: 'FAILED', sampledAt: null }) });
    expect(out.equipment[0].health).toBe('FAILED');
    expect(out.equipment[0].sampledAt).toBe('2026-11-13T14:00:20Z');
  });

  it('ignores events already contained in the board', () => {
    const b = board({ lastEventId: 20 });
    expect(applyMessage(b, { kind: 'service', id: 20, data: svc('SURV', 'FAILED') })).toBe(b);
  });

  it('merges telemetry without touching the last event id', () => {
    const b = board({ equipment: [eq('RAD-N', { values: { a: 1 } })], lastEventId: 5 });
    const out = applyMessage(b, {
      kind: 'telemetry',
      data: {
        equipmentId: 'RAD-N', lastPollAt: '2026-11-13T14:10:00Z', consecutiveFailures: 1,
        values: { b: 2 }, tables: { t: [{ x: 1 }] }, serverTime: '2026-11-13T14:10:00Z',
      },
    });
    expect(out.equipment[0].values).toEqual({ a: 1, b: 2 });
    expect(out.equipment[0].tables).toEqual({ t: [{ x: 1 }] });
    expect(out.equipment[0].contact.lastPollAt).toBe('2026-11-13T14:10:00Z');
    expect(out.equipment[0].contact.consecutiveFailures).toBe(1);
    expect(out.lastEventId).toBe(5);
    expect(b.equipment[0].values).toEqual({ a: 1 });
  });

  it('replaces a service by id', () => {
    const b = board({ services: [svc('SURV', 'OK'), svc('COM', 'OK')], lastEventId: 1 });
    const out = applyMessage(b, { kind: 'service', id: 2, data: svc('SURV', 'FAILED') });
    expect(out.services.map(s => s.status)).toEqual(['FAILED', 'OK']);
    expect(out.lastEventId).toBe(2);
  });

  it('removes closed alarms and sorts by severity', () => {
    let b = board({ lastEventId: 1 });
    b = applyMessage(b, { kind: 'alarm', id: 2, data: alarm('a1', { severity: 'MINOR' }) });
    b = applyMessage(b, { kind: 'alarm', id: 3, data: alarm('a2', { severity: 'CRITICAL' }) });
    expect(b.alarms.map(a => a.id)).toEqual(['a2', 'a1']);
    b = applyMessage(b, { kind: 'alarm', id: 4, data: alarm('a2', { state: 'CLOSED', severity: 'CRITICAL' }) });
    expect(b.alarms.map(a => a.id)).toEqual(['a1']);
    expect(b.lastEventId).toBe(4);
  });

  it('sorts equal severities by raisedAt descending', () => {
    let b = board({ lastEventId: 1 });
    b = applyMessage(b, { kind: 'alarm', id: 2, data: alarm('old', { raisedAt: '2026-11-13T14:00:00Z' }) });
    b = applyMessage(b, { kind: 'alarm', id: 3, data: alarm('new', { raisedAt: '2026-11-13T14:01:00Z' }) });
    expect(b.alarms.map(a => a.id)).toEqual(['new', 'old']);
  });

  it('advances lastEventId on a supervisor event', () => {
    const out = applyMessage(board({ lastEventId: 1 }), {
      kind: 'supervisor', id: 9, data: { type: 'SUPERVISOR_HEARTBEAT', occurredAt: '2026-11-13T14:00:00Z' },
    });
    expect(out.lastEventId).toBe(9);
  });

  it('aFloodMessageSetsTheFlood', () => {
    const start = applyMessage(board({ lastEventId: 1 }), {
      kind: 'flood', id: 5, data: { active: true, since: '2026-11-13T14:00:00Z' },
    });
    expect(start.flood).toEqual({ active: true, since: '2026-11-13T14:00:00Z' });
    expect(start.lastEventId).toBe(5);
    const end = applyMessage(start, { kind: 'flood', id: 6, data: { active: false, since: null } });
    expect(end.flood).toEqual({ active: false, since: null });
  });

  it('ignores a flood message at or below lastEventId', () => {
    const b = board({ lastEventId: 5 });
    expect(applyMessage(b, { kind: 'flood', id: 5, data: { active: true, since: '2026-11-13T14:00:00Z' } })).toBe(b);
  });

  it('does not change the board for open, error, resync and heartbeat', () => {
    const b = board({ lastEventId: 3 });
    expect(applyMessage(b, { kind: 'open' })).toBe(b);
    expect(applyMessage(b, { kind: 'error' })).toBe(b);
    expect(applyMessage(b, { kind: 'resync' })).toBe(b);
    expect(applyMessage(b, { kind: 'heartbeat', data: { serverTime: '2026-11-13T14:00:00Z' } })).toBe(b);
  });
});
