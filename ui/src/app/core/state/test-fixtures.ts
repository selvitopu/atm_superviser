import { AlarmDto, BoardDto, CorrelationDto, EquipmentDto, ServiceDto, ServiceStatus } from '../api/models';

export function eq(id: string, over: Partial<EquipmentDto> = {}): EquipmentDto {
  return {
    id, name: id, type: 'RADAR', group: 'SURVEILLANCE', pair: 'RAD', health: 'OPERATIONAL',
    unknownReason: null, maintenance: false, role: 'MAIN', healthSince: '2026-11-13T13:00:00Z',
    contact: {
      reachable: true, lastContactAt: '2026-11-13T14:00:00Z', lostAt: null,
      lastPollAt: '2026-11-13T14:00:00Z', consecutiveFailures: 0, pollIntervalMs: 5000,
    },
    values: {}, tables: {}, ...over,
  };
}

export function svc(id: string, status: ServiceStatus, over: Partial<ServiceDto> = {}): ServiceDto {
  return {
    id, name: id, status, bestCase: 'OK', redundancyLost: false, reasons: [],
    since: '2026-11-13T13:00:00Z', ...over,
  };
}

export function alarm(id: string, over: Partial<AlarmDto> = {}): AlarmDto {
  return {
    id, equipmentId: 'RAD-N', code: 101, instance: 0, instanceName: '', severity: 'MINOR',
    text: 'text', source: 'SNMP', state: 'ACTIVE_UNACK', suppressed: false, unconfirmed: false,
    raisedAt: '2026-11-13T14:00:00Z', ackedAt: null, ackedBy: null, ackComment: null,
    clearedAt: null, correlation: null, ...over,
  };
}

/** An alarm that the server correlated to `cause` (default: RAD-N 101, rule SENSOR_INPUT). */
export function correlatedAlarm(id: string, causeId: string, over: Partial<AlarmDto> = {},
  correlation: Partial<CorrelationDto> = {}): AlarmDto {
  return alarm(id, {
    equipmentId: 'SDPS-A', code: 401, correlation: {
      causeId, causeEquipmentId: 'RAD-N', causeCode: 101, rule: 'SENSOR_INPUT', ...correlation,
    }, ...over,
  });
}

export function board(over: Partial<BoardDto> = {}): BoardDto {
  return {
    centre: 'Demo ACC', serverTime: '2026-11-13T14:00:00Z', lastEventId: 0,
    services: [], equipment: [], alarms: [], ...over,
  };
}
