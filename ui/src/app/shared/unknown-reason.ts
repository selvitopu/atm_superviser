import { EquipmentDto } from '../core/api/models';

/** Lower-case labels for why an equipment's health is UNKNOWN; the tile and the detail panel show the same words. */
const REASON_LABEL: Record<string, string> = {
  LOST_CONTACT: 'lost contact',
  AWAITING_STATUS: 'awaiting status',
  STATUS_UNAVAILABLE: 'status unavailable',
  NOT_YET_POLLED: 'not yet polled',
  SUPERVISOR_RESTARTED: 'supervisor restarted',
  RESTARTING: 'restarting',
};

/** The reason label of an UNKNOWN equipment, or null when its health is known or the reason has no label. */
export function unknownReasonLabel(e: Pick<EquipmentDto, 'health' | 'unknownReason'>): string | null {
  return e.health === 'UNKNOWN' && e.unknownReason ? (REASON_LABEL[e.unknownReason] ?? null) : null;
}
