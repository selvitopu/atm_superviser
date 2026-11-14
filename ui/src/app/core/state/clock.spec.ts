import { TestBed } from '@angular/core/testing';
import { Clock } from './clock';

describe('Clock', () => {
  beforeEach(() => vi.useFakeTimers({ now: Date.parse('2026-11-13T14:00:00Z') }));
  afterEach(() => vi.useRealTimers());

  it('ticks every second', () => {
    const clock = TestBed.inject(Clock);
    const t0 = clock.now();
    vi.advanceTimersByTime(3000);
    expect(clock.now() - t0).toBe(3000);
  });

  it('applies the server offset', () => {
    const clock = TestBed.inject(Clock);
    clock.setServerTime('2026-11-13T14:00:30Z');
    expect(clock.serverNow()).toBe(Date.parse('2026-11-13T14:00:30Z'));
    vi.advanceTimersByTime(2000);
    expect(clock.serverNow()).toBe(Date.parse('2026-11-13T14:00:32Z'));
  });
});
