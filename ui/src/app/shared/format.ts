/** Formats an instant (ISO string or epoch ms) as HH:mm:ssZ in UTC, never in the browser's zone. */
export function formatUtc(instant: string | number | null | undefined): string {
  if (instant === null || instant === undefined) {
    return '—';
  }
  const ms = typeof instant === 'number' ? instant : Date.parse(instant);
  return Number.isNaN(ms) ? '—' : `${new Date(ms).toISOString().slice(11, 19)}Z`;
}

/** Renders a telemetry value, or — when the server did not send it. */
export function display(value: unknown): string {
  return value === null || value === undefined || value === '' ? '—' : String(value);
}
