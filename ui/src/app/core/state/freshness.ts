export interface Freshness {
  ageMs: number | null;
  stale: boolean;
}

/** Age of the last poll against the server's clock; stale after two missed poll intervals. */
export function freshness(lastPollAt: string | null, pollIntervalMs: number, serverNowMs: number): Freshness {
  if (lastPollAt === null) {
    return { ageMs: null, stale: false };
  }
  const ageMs = serverNowMs - Date.parse(lastPollAt);
  return { ageMs, stale: ageMs > 2 * pollIntervalMs };
}
