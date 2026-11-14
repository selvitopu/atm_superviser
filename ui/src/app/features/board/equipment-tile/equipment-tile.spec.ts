import { provideRouter } from '@angular/router';
import { eq } from '../../../core/state/test-fixtures';
import { clockProvider, render } from '../../../testing/render';
import { EquipmentTile } from './equipment-tile';

const NOW = '2026-11-13T14:00:12Z';
const tile = (over = {}, extra = {}) =>
  render(EquipmentTile, { equipment: eq('RAD-N', { pair: null, role: 'STANDALONE', ...over }), ...extra },
    [provideRouter([]), clockProvider(NOW)]);

describe('EquipmentTile', () => {
  it('tile shows stale age in amber', () => {
    const f = tile({ contact: { ...eq('x').contact, lastPollAt: '2026-11-13T14:00:00Z', pollIntervalMs: 5000 } });
    const age = f.el.querySelector('.age') as HTMLElement;
    expect(age.textContent?.trim()).toBe('12 s ago');
    expect(age.classList).toContain('degraded');
  });

  it('shows no age while fresh', () => {
    const f = tile({ contact: { ...eq('x').contact, lastPollAt: '2026-11-13T14:00:09Z' } });
    expect(f.el.querySelector('.age')).toBeNull();
  });

  it('tile shows maintenance on top of health', () => {
    const f = tile({ health: 'OPERATIONAL', maintenance: true });
    expect(f.text()).toContain('✓');
    expect(f.text()).toContain('MAINT');
  });

  it('shows the role label for a pair member', () => {
    expect(tile({ pair: 'RAD-E', role: 'STANDBY' }).text()).toContain('STANDBY');
    expect(tile().text()).not.toContain('STANDALONE');
  });

  it('shows last contact and greys values when contact is lost', () => {
    const f = tile({
      health: 'UNKNOWN', unknownReason: 'LOST_CONTACT', values: { plotsPerScan: 300 },
      contact: { ...eq('x').contact, lastContactAt: '2026-11-13T13:59:00Z' },
    });
    expect(f.text()).toContain('last contact 13:59:00Z');
    expect(f.el.querySelector('.values')?.classList).toContain('dim');
    expect(f.el.querySelector('.tile')?.className).toContain('h-unknown');
    expect(f.el.querySelector('.tile')?.className).not.toContain('h-operational');
  });

  it('labels other unknown reasons and greys the last-known values while awaiting status or status unavailable', () => {
    const labels: [string, string, boolean][] = [
      ['AWAITING_STATUS', 'awaiting status', true], ['STATUS_UNAVAILABLE', 'status unavailable', true],
      ['NOT_YET_POLLED', 'not yet polled', false], ['SUPERVISOR_RESTARTED', 'supervisor restarted', false],
      ['RESTARTING', 'restarting', false],
    ];
    for (const [reason, label, dim] of labels) {
      const f = tile({ health: 'UNKNOWN', unknownReason: reason, values: { plotsPerScan: 300 } });
      expect(f.el.querySelector('.reason')?.textContent?.trim()).toBe(label);
      expect(f.el.querySelector('.values')?.classList.contains('dim')).toBe(dim);
    }
  });

  it('shows current values at full strength while health is known', () => {
    expect(tile({ values: { plotsPerScan: 300 } }).el.querySelector('.values')?.classList.contains('dim')).toBe(false);
  });

  it('renders — for a missing value', () => {
    expect(tile({ values: {} }).el.querySelector('.values')?.textContent).toContain('—');
  });

  it('links to the equipment route', () => {
    expect(tile().el.querySelector('a')?.getAttribute('href')).toBe('/board/RAD-N');
  });
});
