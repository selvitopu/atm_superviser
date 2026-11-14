import { render } from '../../../testing/render';
import { StatusBadge } from './status-badge';

describe('StatusBadge', () => {
  it('UNKNOWN is never green', () => {
    const f = render(StatusBadge, { status: 'UNKNOWN' });
    expect(f.el.classList).not.toContain('ok');
    expect(f.el.classList).toContain('unknown');
    expect(getComputedStyle(f.el).color).not.toBe('rgb(47, 191, 113)');
    expect(f.text()).toContain('UNKNOWN');
  });

  it('hatches UNKNOWN and colours it with the unknown token', () => {
    render(StatusBadge, { status: 'UNKNOWN' });
    const css = Array.from(document.querySelectorAll('style')).map(s => s.textContent ?? '').join('\n');
    const rule = /\.unknown[^{]*\{[^}]*\}/.exec(css)?.[0] ?? '';
    expect(rule).toContain('repeating-linear-gradient');
    expect(rule).toContain('var(--unknown)');
    expect(rule).not.toContain('var(--ok)');
  });

  it('shows best case for unknown service', () => {
    expect(render(StatusBadge, { status: 'UNKNOWN', bestCase: 'DEGRADED' }).text()).toContain('UNKNOWN · at best DEGRADED');
  });

  it('maps each status to icon, label and class', () => {
    const cases: [string, string, string][] = [
      ['OPERATIONAL', '✓', 'ok'], ['OK', '✓', 'ok'], ['DEGRADED', '▲', 'degraded'], ['FAILED', '✕', 'failed'],
    ];
    for (const [status, icon, cls] of cases) {
      const f = render(StatusBadge, { status });
      expect(f.text()).toBe(`${icon} ${status}`);
      expect(f.el.classList).toContain(cls);
    }
  });
});
