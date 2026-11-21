import { AlarmDto, BoardDto, EquipmentDto, SampleDto, Severity, StreamMessage } from '../api/models';

const SEVERITY_RANK: Record<Severity, number> = {
  CRITICAL: 0, MAJOR: 1, MINOR: 2, WARNING: 3, INDETERMINATE: 4, CLEARED: 5,
};

function compareAlarms(a: AlarmDto, b: AlarmDto): number {
  return SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity] || Date.parse(b.raisedAt) - Date.parse(a.raisedAt);
}

function replaceById<T extends { id: string }>(list: T[], item: T, merge: (current: T) => T = () => item): T[] {
  return list.some(x => x.id === item.id) ? list.map(x => (x.id === item.id ? merge(x) : x)) : [...list, item];
}

function applyEquipment(board: BoardDto, incoming: EquipmentDto): BoardDto['equipment'] {
  return replaceById(board.equipment, incoming, current => ({
    ...incoming,
    values: current.values,
    tables: current.tables,
    sampledAt: current.sampledAt,
    contact: {
      ...incoming.contact,
      lastPollAt: current.contact.lastPollAt,
      consecutiveFailures: current.contact.consecutiveFailures,
    },
  }));
}

function applyContent(board: BoardDto, msg: StreamMessage): BoardDto {
  switch (msg.kind) {
    case 'equipment':
      return { ...board, equipment: applyEquipment(board, msg.data) };
    case 'telemetry': {
      const t = msg.data;
      if (!board.equipment.some(e => e.id === t.equipmentId)) {
        return board;
      }
      return {
        ...board,
        equipment: board.equipment.map(e => e.id !== t.equipmentId ? e : {
          ...e,
          values: { ...e.values, ...t.values },
          tables: { ...e.tables, ...t.tables },
          contact: { ...e.contact, lastPollAt: t.lastPollAt, consecutiveFailures: t.consecutiveFailures },
        }),
      };
    }
    case 'service':
      return { ...board, services: replaceById(board.services, msg.data) };
    case 'alarm': {
      const rest = board.alarms.filter(a => a.id !== msg.data.id);
      const alarms = msg.data.state === 'CLOSED' ? rest : [...rest, msg.data].sort(compareAlarms);
      return { ...board, alarms };
    }
    case 'flood':
      return { ...board, flood: msg.data };
    default:
      return board;
  }
}

/** Applies one stream message to the board. Pure: never mutates, returns the same board for a no-op. */
export function applyMessage(board: BoardDto, msg: StreamMessage): BoardDto {
  const id = 'id' in msg ? msg.id : null;
  if (id !== null && id <= board.lastEventId) {
    return board;
  }
  const next = applyContent(board, msg);
  return id === null ? next : { ...next, lastEventId: Math.max(board.lastEventId, id) };
}

/** What the replay store folds in: the live messages plus the replay-only {@code sample}. */
export type ReplayStreamMessage = StreamMessage | { kind: 'sample'; id: number; data: SampleDto };

/**
 * Applies one replay message. Only the replay store calls this: {@code sample} is not a live kind, so
 * {@link applyMessage} and the live path never see it. A sample sets the unit's values and {@code sampledAt}.
 */
export function applyReplayMessage(board: BoardDto, msg: ReplayStreamMessage): BoardDto {
  if (msg.kind !== 'sample') {
    return applyMessage(board, msg);
  }
  if (msg.id <= board.lastEventId) {
    return board;
  }
  const s = msg.data;
  const equipment = board.equipment.map(e => e.id !== s.equipmentId ? e : {
    ...e,
    values: { ...e.values, ...s.values },
    tables: { ...e.tables, ...s.tables },
    sampledAt: s.observedAt,
  });
  return { ...board, equipment, lastEventId: msg.id };
}
