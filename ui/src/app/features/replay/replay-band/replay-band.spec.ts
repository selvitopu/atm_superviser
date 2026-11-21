import { provideRouter } from '@angular/router';
import { render } from '../../../testing/render';
import { ReplayBand } from './replay-band';

const at = Date.parse('2026-11-20T14:02:11Z');

describe('ReplayBand', () => {
  const band = (from: string, to: string) => render(ReplayBand, { at, from: Date.parse(from), to: Date.parse(to) },
    [provideRouter([])]);

  it('showsTheExactBandCopy', () => {
    const f = band('2026-11-20T13:00:00Z', '2026-11-20T15:00:00Z');
    expect(f.text()).toContain('REPLAY · 14:02:11Z · NOT LIVE · values ≤30 s · no freshness ages');
    expect(f.text()).toContain('Back to live');
    expect(f.el.querySelector('[role=banner]')).not.toBeNull();
  });

  it('addsTheDateWhenTheWindowSpansDays', () => {
    const f = band('2026-11-19T23:00:00Z', '2026-11-20T15:00:00Z');
    expect(f.text()).toContain('REPLAY · 2026-11-20 14:02:11Z · NOT LIVE');
  });

  it('cannotBeDismissed', () => {
    const f = band('2026-11-20T13:00:00Z', '2026-11-20T15:00:00Z');
    expect(f.all('button').length).toBe(0);
    expect(f.all('a').map(a => a.textContent?.trim())).toEqual(['Back to live']);
  });
});
