import { Injectable, inject, signal } from '@angular/core';
import { Observable, Subscription } from 'rxjs';
import { BoardApi } from '../api/board-api';
import { LiveStream } from '../api/live-stream';
import { AckResultDto, BoardDto, StreamMessage } from '../api/models';
import { applyMessage } from './apply-message';
import { BoardSource } from './board-source';
import { Clock } from './clock';

export type Connection = 'LIVE' | 'RECONNECTING' | 'OFFLINE';

const BACKOFF_MS = [1000, 2000, 4000, 8000, 15000];
const WATCHDOG_MS = 20_000;
const NOT_LIVE_MS = 10_000;
const OFFLINE_MS = 30_000;

/**
 * Holds the live board: fetches it, follows the SSE stream from its lastEventId and reconnects with backoff.
 * OFFLINE is a display state only; reconnect attempts never stop.
 */
@Injectable()
export class LiveBoardStore extends BoardSource {
  private readonly api = inject(BoardApi);
  private readonly stream = inject(LiveStream);
  private readonly clock = inject(Clock);

  private readonly boardSignal = signal<BoardDto | null>(null);
  private readonly connectionSignal = signal<Connection>('RECONNECTING');
  private readonly dataNotLiveSignal = signal(false);

  readonly board = this.boardSignal.asReadonly();
  readonly connection = this.connectionSignal.asReadonly();
  readonly dataNotLive = this.dataNotLiveSignal.asReadonly();

  private running = false;
  private backoffIndex = 0;
  private fetchSub?: Subscription;
  private streamSub?: Subscription;
  private reconnectTimer?: ReturnType<typeof setTimeout>;
  private watchdogTimer?: ReturnType<typeof setTimeout>;
  private notLiveTimer?: ReturnType<typeof setTimeout>;
  private offlineTimer?: ReturnType<typeof setTimeout>;

  start(): void {
    if (this.running) {
      return;
    }
    this.running = true;
    this.beginNotLive();
    this.fetchBoard();
  }

  stop(): void {
    this.running = false;
    this.fetchSub?.unsubscribe();
    this.closeStream();
    clearTimeout(this.reconnectTimer);
    clearTimeout(this.notLiveTimer);
    clearTimeout(this.offlineTimer);
  }

  /** Board changes arrive through the stream's alarm events; the board is not patched here. */
  ackSelected(ids: string[]): Observable<AckResultDto> {
    return this.api.ackMany(ids);
  }

  private fetchBoard(): void {
    this.fetchSub?.unsubscribe();
    this.fetchSub = this.api.board().subscribe({
      next: board => {
        this.boardSignal.set(board);
        this.clock.setServerTime(board.serverTime);
        this.openStream(board.lastEventId);
      },
      error: () => this.scheduleReconnect(),
    });
  }

  private openStream(lastEventId: number): void {
    this.closeStream();
    this.armWatchdog();
    this.streamSub = this.stream.connect(lastEventId).subscribe(msg => this.onMessage(msg));
  }

  private closeStream(): void {
    clearTimeout(this.watchdogTimer);
    this.streamSub?.unsubscribe();
    this.streamSub = undefined;
  }

  private onMessage(msg: StreamMessage): void {
    this.armWatchdog();
    switch (msg.kind) {
      case 'open':
        this.onOpen();
        return;
      case 'error':
      case 'resync':
        this.onDisconnect();
        return;
      case 'heartbeat':
      case 'telemetry':
        this.clock.setServerTime(msg.data.serverTime);
        break;
    }
    this.boardSignal.update(board => (board ? applyMessage(board, msg) : board));
  }

  private onOpen(): void {
    this.backoffIndex = 0;
    clearTimeout(this.notLiveTimer);
    clearTimeout(this.offlineTimer);
    this.dataNotLiveSignal.set(false);
    this.connectionSignal.set('LIVE');
  }

  private onDisconnect(): void {
    this.closeStream();
    if (this.connectionSignal() === 'LIVE') {
      this.beginNotLive();
    }
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (!this.running) {
      return;
    }
    if (this.connectionSignal() === 'LIVE') {
      this.connectionSignal.set('RECONNECTING');
    }
    const delay = BACKOFF_MS[Math.min(this.backoffIndex, BACKOFF_MS.length - 1)];
    this.backoffIndex++;
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = setTimeout(() => this.fetchBoard(), delay);
  }

  private armWatchdog(): void {
    clearTimeout(this.watchdogTimer);
    this.watchdogTimer = setTimeout(() => this.onDisconnect(), WATCHDOG_MS);
  }

  /** Starts the clocks that turn a connection that is not LIVE into "data not live" and then OFFLINE. */
  private beginNotLive(): void {
    this.connectionSignal.set('RECONNECTING');
    clearTimeout(this.notLiveTimer);
    clearTimeout(this.offlineTimer);
    this.notLiveTimer = setTimeout(() => this.dataNotLiveSignal.set(true), NOT_LIVE_MS);
    this.offlineTimer = setTimeout(() => this.connectionSignal.set('OFFLINE'), OFFLINE_MS);
  }
}
