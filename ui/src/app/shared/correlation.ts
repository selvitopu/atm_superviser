import { CorrelationDto } from '../core/api/models';

/** Why an alarm sits under its cause, in words. */
export function ruleNote(c: CorrelationDto): string {
  switch (c.rule) {
    case 'SENSOR_INPUT': return `input from ${c.causeEquipmentId}`;
    case 'SWITCHOVER': return `switchover after ${c.causeEquipmentId} failed`;
    case 'SAME_UNIT': return 'on the failed unit';
  }
}

/** The history cell: the cause and the rule note. */
export function correlatedCell(c: CorrelationDto): string {
  return `↳ ${c.causeEquipmentId} ${c.causeCode} · ${ruleNote(c)}`;
}
