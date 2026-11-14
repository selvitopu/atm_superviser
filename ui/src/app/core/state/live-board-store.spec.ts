import { TestBed } from '@angular/core/testing';
import { Observable, of, Subject } from 'rxjs';
import { BoardApi } from '../api/board-api';
import { LiveStream } from '../api/live-stream';
import { AckResultDto, BoardDto, StreamMessage } from '../api/models';
import { board } from './test-fixtures';
import { Clock } from './clock';
import { BoardSource } from './board-source';
import { LiveBoardStore } from './live-board-store';

class FakeApi {
  boardCalls = 0;
  ackedMany: string[][] = [];
  next: BoardDto = board({ lastEventId: 42, serverTime: '2026-11-13T14:00:00Z' });
  board() { this.boardCalls++; return of(this.next); }
  ackMany(ids: string[]): Observable<AckResultDto> {
    this.ackedMany.push(ids);
    return of({ acknowledged: [], notFound: [] });
  }
}

class FakeStream {
  connectedWith: number[] = [];
  closed = 0;
  private subject = new Subject<StreamMessage>();
  connect(lastEventId: number): Observable<StreamMessage> {
    this.connectedWith.push(lastEventId);
    return new Observable<StreamMessage>(sub => {
      const inner = this.subject.subscribe(sub);
      return () => { this.closed++; inner.unsubscribe(); };
    });
  }
  emit(m: StreamMessage) { this.subject.next(m); }
}

describe('LiveBoardStore', () => {
  let api: FakeApi;
  let stream: FakeStream;
  let store: LiveBoardStore;

  beforeEach(() => {
    vi.useFakeTimers({ now: Date.parse('2026-11-13T14:00:00Z') });
    api = new FakeApi();
    stream = new FakeStream();
    TestBed.configureTestingModule({
      providers: [
        { provide: BoardApi, useValue: api },
        { provide: LiveStream, useValue: stream },
        LiveBoardStore,
        { provide: BoardSource, useExisting: LiveBoardStore },
      ],
    });
    store = TestBed.inject(LiveBoardStore);
  });
  afterEach(() => { store.stop(); vi.useRealTimers(); });

  it('connectsFromBoardLastEventId', () => {
    store.start();
    expect(stream.connectedWith).toEqual([42]);
    expect(store.board()?.lastEventId).toBe(42);
    expect(TestBed.inject(BoardSource).board()).toBe(store.board());
  });

  it('is LIVE only after the stream opens', () => {
    store.start();
    expect(store.connection()).toBe('RECONNECTING');
    stream.emit({ kind: 'open' });
    expect(store.connection()).toBe('LIVE');
    expect(store.dataNotLive()).toBe(false);
  });

  it('applies stream messages to the board', () => {
    store.start();
    stream.emit({ kind: 'open' });
    api.next = board();
    stream.emit({ kind: 'service', id: 43, data: { id: 'SURV', name: 'SURV', status: 'FAILED', bestCase: 'OK', redundancyLost: false, reasons: [], since: '2026-11-13T14:00:00Z' } });
    expect(store.board()?.lastEventId).toBe(43);
  });

  it('reconnects with backoff and refetches the board', () => {
    store.start();
    stream.emit({ kind: 'open' });
    stream.emit({ kind: 'error' });
    expect(store.connection()).toBe('RECONNECTING');
    vi.advanceTimersByTime(1000);
    expect(api.boardCalls).toBe(2);
    expect(stream.connectedWith).toEqual([42, 42]);
  });

  it('doubles the backoff up to 15 s and resets it on open', () => {
    store.start();
    for (const expected of [1000, 2000, 4000, 8000, 15000, 15000]) {
      const before = api.boardCalls;
      stream.emit({ kind: 'error' });
      vi.advanceTimersByTime(expected - 1);
      expect(api.boardCalls).toBe(before);
      vi.advanceTimersByTime(1);
      expect(api.boardCalls).toBe(before + 1);
    }
    stream.emit({ kind: 'open' });
    stream.emit({ kind: 'error' });
    vi.advanceTimersByTime(1000);
    expect(api.boardCalls).toBe(8);
  });

  it('resync closes the stream and triggers refetch', () => {
    store.start();
    stream.emit({ kind: 'open' });
    stream.emit({ kind: 'resync' });
    expect(stream.closed).toBe(1);
    expect(store.connection()).toBe('RECONNECTING');
    api.next = board({ lastEventId: 50 });
    vi.advanceTimersByTime(1000);
    expect(api.boardCalls).toBe(2);
    expect(stream.connectedWith).toEqual([42, 50]);
  });

  it('watchdog reconnects after 20 s of silence', () => {
    store.start();
    stream.emit({ kind: 'open' });
    vi.advanceTimersByTime(19000);
    expect(store.connection()).toBe('LIVE');
    vi.advanceTimersByTime(1000);
    expect(store.connection()).toBe('RECONNECTING');
  });

  it('any message resets the watchdog', () => {
    store.start();
    stream.emit({ kind: 'open' });
    vi.advanceTimersByTime(15000);
    stream.emit({ kind: 'heartbeat', data: { serverTime: '2026-11-13T14:00:15Z' } });
    vi.advanceTimersByTime(15000);
    expect(store.connection()).toBe('LIVE');
  });

  it('dataNotLive after 10 s without LIVE', () => {
    store.start();
    stream.emit({ kind: 'open' });
    stream.emit({ kind: 'error' });
    vi.advanceTimersByTime(9000);
    expect(store.dataNotLive()).toBe(false);
    vi.advanceTimersByTime(1500);
    expect(store.dataNotLive()).toBe(true);
  });

  it('goes OFFLINE after 30 s without LIVE and recovers on open', () => {
    store.start();
    stream.emit({ kind: 'open' });
    stream.emit({ kind: 'error' });
    vi.advanceTimersByTime(31000);
    expect(store.connection()).toBe('OFFLINE');
    stream.emit({ kind: 'open' });
    expect(store.connection()).toBe('LIVE');
    expect(store.dataNotLive()).toBe(false);
  });

  it('heartbeat and telemetry set the server time', () => {
    store.start();
    stream.emit({ kind: 'open' });
    const clock = TestBed.inject(Clock);
    stream.emit({ kind: 'heartbeat', data: { serverTime: '2026-11-13T14:10:00Z' } });
    expect(clock.serverNow()).toBe(Date.parse('2026-11-13T14:10:00Z'));
  });

  it('keeps retrying when the board fetch fails', () => {
    store.start();
    stream.emit({ kind: 'error' });
    let fail = true;
    api.board = () => { api.boardCalls++; return fail ? new Observable<BoardDto>(s => s.error(new Error('down'))) : of(api.next); };
    vi.advanceTimersByTime(1000);
    expect(api.boardCalls).toBe(2);
    expect(store.connection()).toBe('RECONNECTING');
    fail = false;
    vi.advanceTimersByTime(2000);
    expect(api.boardCalls).toBe(3);
    expect(stream.connectedWith).toEqual([42, 42]);
  });

  it('ackSelected returns the ackMany observable without touching the board', () => {
    store.start();
    const before = store.board();
    let result: AckResultDto | undefined;
    store.ackSelected(['a1', 'a2']).subscribe(r => (result = r));
    expect(api.ackedMany).toEqual([['a1', 'a2']]);
    expect(result).toEqual({ acknowledged: [], notFound: [] });
    expect(store.board()).toBe(before);
  });

  it('stop closes the stream and cancels timers', () => {
    store.start();
    stream.emit({ kind: 'open' });
    store.stop();
    expect(stream.closed).toBe(1);
    vi.advanceTimersByTime(60000);
    expect(api.boardCalls).toBe(1);
  });
});
