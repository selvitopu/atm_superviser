import { TimelineDto } from '../../../core/api/models';
import { render } from '../../../testing/render';
import { ReplayTimeline } from './replay-timeline';

const from = Date.parse('2026-11-20T14:00:00Z');
const to = Date.parse('2026-11-20T15:00:00Z');

function data(over: Partial<TimelineDto> = {}): TimelineDto {
  return {
    markers: [{ at: '2026-11-20T14:30:00Z', equipmentId: 'RAD-N', code: 101, severity: 'CRITICAL', text: 'Sensor failed',
      suppressed: false }],
    truncated: false, floods: [], gaps: [], restarts: [], ...over,
  };
}
const setup = (d: TimelineDto | null) => render(ReplayTimeline, { from, to, at: from, data: d });

describe('ReplayTimeline', () => {
  it('drawsAMarkerPerAlarmWithItsAccessibleName', () => {
    const f = setup(data());
    const markers = f.all('button.marker');
    expect(markers.length).toBe(1);
    expect(markers[0].getAttribute('aria-label')).toBe('14:30:00Z CRITICAL RAD-N Sensor failed');
    expect(markers[0].style.left).toBe('50%');
  });

  it('shadesFloodsAndGaps', () => {
    const f = setup(data({
      floods: [{ start: '2026-11-20T13:50:00Z', end: '2026-11-20T14:15:00Z' }],
      gaps: [{ start: '2026-11-20T14:45:00Z', end: null }],
    }));
    const flood = f.el.querySelector('.span.flood') as HTMLElement;
    const gap = f.el.querySelector('.span.gap') as HTMLElement;
    expect(flood.style.left).toBe('0%'); // clipped to the window start
    expect(flood.style.width).toBe('25%');
    expect(gap.style.left).toBe('75%');
    expect(gap.style.width).toBe('25%'); // open: runs to the end
  });

  it('clickingSeeks', () => {
    const f = setup(data());
    const seeks: number[] = [];
    f.fixture.componentInstance.seek.subscribe(v => seeks.push(v));
    const track = f.el.querySelector('.track') as HTMLElement;
    track.getBoundingClientRect = () => ({ left: 10, width: 200 }) as DOMRect;
    track.dispatchEvent(new MouseEvent('click', { clientX: 110 }));
    expect(seeks).toEqual([from + 30 * 60_000]);
  });

  it('showsTheCapNotice', () => {
    expect(setup(data({ truncated: true })).text()).toContain('Too many alarms to mark; showing the newest 2,000');
    expect(setup(data()).text()).not.toContain('Too many alarms');
  });
});
