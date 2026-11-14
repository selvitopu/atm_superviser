import { freshness } from './freshness';

describe('freshness', () => {
  it('freshnessUsesServerOffset', () => {
    expect(freshness('2026-11-13T14:00:00Z', 5000, Date.parse('2026-11-13T14:00:11Z')))
      .toEqual({ ageMs: 11000, stale: true });
  });

  it('is fresh within two poll intervals', () => {
    expect(freshness('2026-11-13T14:00:00Z', 5000, Date.parse('2026-11-13T14:00:10Z')))
      .toEqual({ ageMs: 10000, stale: false });
  });

  it('has no age and is not stale without a poll', () => {
    expect(freshness(null, 5000, Date.now())).toEqual({ ageMs: null, stale: false });
  });
});
