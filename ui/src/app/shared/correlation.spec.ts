import { CorrelationDto } from '../core/api/models';
import { correlatedCell, ruleNote } from './correlation';

const c = (rule: CorrelationDto['rule']): CorrelationDto =>
  ({ causeId: 'x', causeEquipmentId: 'RAD-N', causeCode: 101, rule });

describe('correlation', () => {
  it('writesTheRuleNotes', () => {
    expect(ruleNote(c('SENSOR_INPUT'))).toBe('input from RAD-N');
    expect(ruleNote(c('SWITCHOVER'))).toBe('switchover after RAD-N failed');
    expect(ruleNote(c('SAME_UNIT'))).toBe('on the failed unit');
  });

  it('writesTheHistoryCell', () => {
    expect(correlatedCell(c('SENSOR_INPUT'))).toBe('↳ RAD-N 101 · input from RAD-N');
  });
});
