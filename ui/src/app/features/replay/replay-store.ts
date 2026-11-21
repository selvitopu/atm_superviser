import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { ReplayApi } from '../../core/api/replay-api';
import { BoardDto, ReplayEventDto } from '../../core/api/models';
import { ReplayStreamMessage, applyReplayMessage } from '../../core/state/apply-message';
import { BoardSource } from '../../core/state/board-source';

export type ReplaySpeed = 1 | 4 | 16 | 60;

export interface TickerEntry {
  at: number;
  summary: string;
}

const TICK_MS = 100;
const PAGE_SIZE = 500;
const LOOKAHEAD_MS = 60_000;
const TICKER_SIZE = 20;
const APPLIED_KINDS = new Set(['equipment', 'service', 'alarm', 'flood', 'supervisor', 'sample']);

/**
 * A second {@link BoardSource}: loads the board at an instant from the recorded history and plays the events after it
 * forward on a virtual clock. Times are epoch milliseconds. It injects only the history API (HttpClient): it has no
 * dependency on the live stream or the live store, so a replay can never show, or be changed by, live data.
 * Provided by the Replay page only. {@code previous()} steps to the newest event time seen in this window, or to the
 * window start when none is known: the server has no previous-event endpoint.
 */
@Injectable()
export class ReplayStore extends BoardSource {
  private readonly api = inject(ReplayApi);

  private readonly boardSignal = signal<BoardDto | null>(null);
  private readonly atSignal = signal(0);
  private readonly playingSignal = signal(false);
  private readonly speedSignal = signal<ReplaySpeed>(1);
  private readonly noDataSignal = signal(false);
  private readonly tickerSignal = signal<TickerEntry[]>([]);
  private readonly windowSignal = signal({ from: 0, to: 0 });
  private readonly errorSignal = signal(false);

  readonly board = this.boardSignal.asReadonly();
  /** The virtual clock. */
  readonly at = this.atSignal.asReadonly();
  readonly playing = this.playingSignal.asReadonly();
  readonly speed = this.speedSignal.asReadonly();
  readonly noData = this.noDataSignal.asReadonly();
  /** The last 20 applied events, newest first; samples are not listed. */
  readonly ticker = this.tickerSignal.asReadonly();
  readonly window = this.windowSignal.asReadonly();
  /** True when the board or an events page could not be loaded; cleared by the next seek. */
  readonly error = this.errorSignal.asReadonly();

  private generation = 0;
  private loading = false;
  /** The last board load failed: nothing plays until the next successful seek. */
  private boardFailed = false;
  private timer?: ReturnType<typeof setInterval>;
  private lastTick = 0;
  private boardSub?: Subscription;
  private eventsSub?: Subscription;

  // The prefetch buffer: events after the board, in id order, not yet applied.
  private buffer: ReplayEventDto[] = [];
  private cursor = 0;
  private more = true;
  private fetchedThrough = 0;
  private fetching = false;
  private afterFetch: (() => void)[] = [];

  // The gap rule (spec R3), as the browser applies it during play.
  private thresholdMs = 90_000;
  private seekNoData = false;
  private lastNonSampleAt: number | null = null;
  private lastWasStopping = false;

  /** Times of every non-sample event seen in this window; "previous" walks back over them. */
  private knownTimes: number[] = [];

  constructor() {
    super();
    inject(DestroyRef).onDestroy(() => this.cancelAll());
  }

  /** Sets the window and loads the board at {@code at}; playback is paused. */
  open(from: number, to: number, at: number): void {
    this.pause();
    this.windowSignal.set({ from, to });
    this.knownTimes = [];
    this.seekTo(at, false);
  }

  /** Loads the board at {@code at}, then prefetches the events after it. */
  seek(at: number): void {
    this.seekTo(at, false);
  }

  play(): void {
    if (this.playing() || this.boardFailed || this.atSignal() >= this.windowSignal().to) {
      return;
    }
    this.errorSignal.set(false);
    this.playingSignal.set(true);
    this.lastTick = Date.now();
    this.timer = setInterval(() => this.tick(), TICK_MS);
    this.prefetch(); // an explicit play retries a page that failed
  }

  pause(): void {
    this.playingSignal.set(false);
    clearInterval(this.timer);
    this.timer = undefined;
  }

  setSpeed(speed: ReplaySpeed): void {
    this.speedSignal.set(speed);
  }

  /** Applies the events up to and including the next one that is not a sample, and moves the clock to it. */
  next(): void {
    const step = () => {
      let moved = false;
      while (this.buffer.length > 0 && !moved) {
        const event = this.buffer.shift()!;
        const when = Date.parse(event.occurredAt);
        this.atSignal.update(at => Math.max(at, when));
        moved = !this.isSample(event);
        if (!this.applyEvent(event)) {
          return;
        }
      }
      this.evaluateGap();
      this.prefetch();
    };
    if (this.loading) {
      return;
    }
    if (this.buffer.length === 0 && this.more) {
      this.afterFetch.push(step);
      this.prefetch(true);
    } else {
      step();
    }
  }

  /** Seeks to the newest known event before the clock, or to the window start when none is known. */
  previous(): void {
    const at = this.atSignal();
    const earlier = this.knownTimes.filter(time => time < at);
    const target = earlier.length > 0 ? Math.max(...earlier) : this.windowSignal().from;
    if (target < at) {
      this.seek(target);
    }
  }

  private seekTo(requested: number, keepTicker: boolean): void {
    const { from, to } = this.windowSignal();
    const at = Math.min(Math.max(requested, from), to);
    this.cancelFetches();
    const generation = ++this.generation;
    this.loading = true;
    this.boardFailed = false;
    this.errorSignal.set(false);
    this.atSignal.set(at);
    if (!keepTicker) {
      this.tickerSignal.set([]);
    }
    this.buffer = [];
    this.afterFetch = [];
    this.more = true;
    this.fetching = false;
    this.boardSub = this.api.boardAt(new Date(at).toISOString()).subscribe({
      next: replay => {
        if (generation !== this.generation) {
          return;
        }
        this.boardSignal.set(replay.board);
        this.thresholdMs = replay.gapThresholdSeconds * 1000;
        this.seekNoData = replay.noData;
        this.lastNonSampleAt = replay.lastRecordedAt === null ? null : Date.parse(replay.lastRecordedAt);
        this.lastWasStopping = replay.lastRecordedType === 'SUPERVISOR_STOPPING';
        this.noDataSignal.set(replay.noData);
        this.cursor = replay.board.lastEventId;
        this.fetchedThrough = at;
        this.loading = false;
        this.lastTick = Date.now();
        this.prefetch();
      },
      error: () => {
        if (generation === this.generation) {
          // loading stays true: tick, prefetch and next wait for the next successful seek
          this.boardFailed = true;
          this.pause();
          this.errorSignal.set(true);
        }
      },
    });
  }

  private tick(): void {
    if (this.loading) {
      this.lastTick = Date.now();
      return;
    }
    const now = Date.now();
    const elapsed = now - this.lastTick;
    this.lastTick = now;
    const { to } = this.windowSignal();
    // The clock waits for the next page rather than run past what is loaded.
    const limit = this.more ? Math.min(to, Math.max(this.fetchedThrough, this.atSignal())) : to;
    const at = Math.min(this.atSignal() + elapsed * this.speedSignal(), limit);
    this.atSignal.set(at);
    this.applyDue(at);
    if (this.loading) {
      return;
    }
    this.evaluateGap();
    this.prefetch();
    if (at >= to && !this.more) {
      this.pause();
    }
  }

  private applyDue(at: number): void {
    while (this.buffer.length > 0 && Date.parse(this.buffer[0].occurredAt) <= at) {
      if (!this.applyEvent(this.buffer.shift()!)) {
        return;
      }
    }
  }

  /** Applies one event; returns false when it started a reload, after which the rest of the buffer is gone. */
  private applyEvent(event: ReplayEventDto): boolean {
    const when = Date.parse(event.occurredAt);
    const kind = event.message.kind;
    if (APPLIED_KINDS.has(kind)) {
      const message = { kind, id: event.id, data: event.message.data } as ReplayStreamMessage;
      this.boardSignal.update(board => (board ? applyReplayMessage(board, message) : board));
    }
    if (kind === 'sample') {
      return true;
    }
    this.lastNonSampleAt = when;
    this.lastWasStopping = this.typeOf(event) === 'SUPERVISOR_STOPPING';
    if (!this.knownTimes.includes(when)) {
      this.knownTimes.push(when);
    }
    this.tickerSignal.update(list => [{ at: when, summary: event.summary }, ...list].slice(0, TICKER_SIZE));
    if (this.typeOf(event) === 'SUPERVISOR_STARTED') {
      // A restart changes every unit without per-unit events (R2): the server rebuilds the board at that instant.
      this.seekTo(when, true);
      return false;
    }
    this.evaluateGap();
    return true;
  }

  private typeOf(event: ReplayEventDto): string {
    const data = event.message.data as { type?: string } | null;
    return event.message.kind === 'supervisor' && data?.type ? data.type : event.type;
  }

  private isSample(event: ReplayEventDto): boolean {
    return event.message.kind === 'sample';
  }

  /**
   * The gap rule: no data once the last non-sample event is a threshold old, or was the supervisor stopping. A seek
   * seeds that event from the board's {@code lastRecorded*} fields; with none recorded the seek's answer stands.
   */
  private evaluateGap(): void {
    if (this.lastNonSampleAt === null) {
      this.noDataSignal.set(this.seekNoData);
      return;
    }
    this.noDataSignal.set(this.lastWasStopping || this.atSignal() - this.lastNonSampleAt >= this.thresholdMs);
  }

  /** Keeps at least the next 60 s of virtual time loaded; {@code force} loads one page whatever the buffer holds. */
  private prefetch(force = false): void {
    if (this.fetching || this.loading || !this.more) {
      return;
    }
    if (!force && this.fetchedThrough >= this.atSignal() + LOOKAHEAD_MS) {
      return;
    }
    const generation = this.generation;
    this.fetching = true;
    this.eventsSub = this.api.events(this.cursor, new Date(this.windowSignal().to).toISOString(), PAGE_SIZE).subscribe({
      next: page => {
        if (generation !== this.generation) {
          return;
        }
        this.fetching = false;
        this.buffer.push(...page.events);
        if (page.events.length > 0) {
          const last = page.events[page.events.length - 1];
          this.cursor = last.id;
          this.fetchedThrough = Date.parse(last.occurredAt);
        }
        this.more = page.more;
        const waiting = this.afterFetch;
        this.afterFetch = [];
        waiting.forEach(fn => fn());
        this.applyDue(this.atSignal());
        this.evaluateGap();
        this.prefetch();
      },
      error: () => {
        if (generation === this.generation) {
          this.fetching = false;
          this.pause();
          this.errorSignal.set(true);
        }
      },
    });
  }

  private cancelFetches(): void {
    this.boardSub?.unsubscribe();
    this.eventsSub?.unsubscribe();
  }

  private cancelAll(): void {
    this.pause();
    this.generation++;
    this.cancelFetches();
  }
}
