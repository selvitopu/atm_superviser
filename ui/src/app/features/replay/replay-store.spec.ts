import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Observable, of, Subject } from 'rxjs';
import { BoardApi } from '../../core/api/board-api';
import { LiveStream } from '../../core/api/live-stream';
import { BoardDto, ReplayBoardDto, ReplayEventDto, ReplayEventsDto, StreamMessage } from '../../core/api/models';
import { BoardSource } from '../../core/state/board-source';
import { LiveBoardStore } from '../../core/state/live-board-store';
import { board, eq } from '../../core/state/test-fixtures';
import { ReplayStore } from './replay-store';

const FROM = Date.parse('2026-11-13T14:00:00Z');
const TO = Date.parse('2026-11-13T15:00:00Z');
const iso = (ms: number) => new Date(ms).toISOString();
const t = (secondsAfterFrom: number) => FROM + secondsAfterFrom * 1000;

function boardAt(over: Partial<ReplayBoardDto> = {}, b: Partial<BoardDto> = {}): ReplayBoardDto {
  return {
    at: iso(FROM), noData: false, gapThresholdSeconds: 90,
    board: board({ lastEventId: 10, equipment: [eq('RAD-N', { values: { plots: 1 } })], ...b }), ...over,
  };
}

function ev(id: number, seconds: number, kind: string, data: unknown, type = 'X', summary = `event ${id}`): ReplayEventDto {
  return { id, occurredAt: iso(t(seconds)), type, equipmentId: null, actor: null, summary, message: { kind, data } };
}

const failed = (id: number, seconds: number) =>
  ev(id, seconds, 'equipment', eq('RAD-N', { health: 'FAILED' }), 'EQUIPMENT_HEALTH_CHANGED', `RAD-N FAILED ${id}`);
const sample = (id: number, seconds: number, plots = 1) =>
  ev(id, seconds, 'sample', { equipmentId: 'RAD-N', observedAt: iso(t(seconds)), values: { plots }, tables: {} },
    'TELEMETRY_SAMPLED');
const heartbeat = (id: number, seconds: number) =>
  ev(id, seconds, 'supervisor', { type: 'SUPERVISOR_HEARTBEAT', occurredAt: iso(t(seconds)) }, 'SUPERVISOR_HEARTBEAT');
const stopping = (id: number, seconds: number) =>
  ev(id, seconds, 'supervisor', { type: 'SUPERVISOR_STOPPING', occurredAt: iso(t(seconds)) }, 'SUPERVISOR_STOPPING');
const started = (id: number, seconds: number) =>
  ev(id, seconds, 'supervisor', { type: 'SUPERVISOR_STARTED', occurredAt: iso(t(seconds)) }, 'SUPERVISOR_STARTED');

describe('ReplayStore', () => {
  let http: HttpTestingController;
  let store: ReplayStore;

  beforeEach(() => {
    vi.useFakeTimers({ now: FROM });
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ReplayStore,
        { provide: BoardSource, useExisting: ReplayStore }],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(ReplayStore);
  });
  afterEach(() => { store.pause(); vi.useRealTimers(); });

  const boardReq = () => http.expectOne(r => r.url === '/api/v1/history/board');
  const eventsReq = () => http.expectOne(r => r.url === '/api/v1/history/events');
  const flushEvents = (events: ReplayEventDto[], more = false) => {
    const req = eventsReq();
    req.flush({ events, more } satisfies ReplayEventsDto);
    return req;
  };

  /** Opens the window at its start, answers the board and the first page of events. */
  function openWith(events: ReplayEventDto[], more = false, at = FROM, boardOver: Partial<ReplayBoardDto> = {}) {
    store.open(FROM, TO, at);
    boardReq().flush(boardAt({ at: iso(at), ...boardOver }));
    flushEvents(events, more);
  }

  it('seekLoadsTheBoardAndPrefetches', () => {
    store.open(FROM, TO, t(30));
    const b = boardReq();
    expect(b.request.params.get('at')).toBe(iso(t(30)));
    expect(store.board()).toBeNull();
    b.flush(boardAt({ at: iso(t(30)) }));
    const e = eventsReq();
    expect(e.request.params.get('after')).toBe('10');
    expect(e.request.params.get('until')).toBe(iso(TO));
    // The first page ends 20 s ahead and more remains: the next page is requested at once.
    e.flush({ events: [failed(11, 40), sample(12, 50)], more: true });
    const next = eventsReq();
    expect(next.request.params.get('after')).toBe('12');
    next.flush({ events: [heartbeat(13, 100)], more: false });
    expect(store.board()?.lastEventId).toBe(10);
    expect(store.at()).toBe(t(30));
    expect(store.window()).toEqual({ from: FROM, to: TO });
    http.verify();
  });

  it('playAppliesEventsInOrderAtTheChosenSpeed', () => {
    openWith([failed(11, 10), sample(12, 20, 99), heartbeat(13, 40)]);
    store.setSpeed(4);
    store.play();
    expect(store.playing()).toBe(true);
    vi.advanceTimersByTime(2000);
    expect(store.at()).toBe(t(8));
    expect(store.board()?.equipment[0].health).toBe('OPERATIONAL');
    vi.advanceTimersByTime(1000);
    expect(store.at()).toBe(t(12));
    expect(store.board()?.equipment[0].health).toBe('FAILED');
    expect(store.board()?.lastEventId).toBe(11);
    vi.advanceTimersByTime(2000);
    expect(store.at()).toBe(t(20));
    expect(store.board()?.equipment[0].values['plots']).toBe(99);
    expect(store.board()?.lastEventId).toBe(12);
  });

  it('playbackStopsAtTheWindowEnd', () => {
    openWith([], false, TO - 5000);
    store.setSpeed(60);
    store.play();
    vi.advanceTimersByTime(2000);
    expect(store.at()).toBe(TO);
    expect(store.playing()).toBe(false);
    vi.advanceTimersByTime(5000);
    expect(store.at()).toBe(TO);
  });

  it('nextAppliesOneEventAndMovesTheClock', () => {
    openWith([sample(11, 5, 7), failed(12, 10), failed(13, 20)]);
    store.next();
    expect(store.at()).toBe(t(10));
    expect(store.board()?.lastEventId).toBe(12);
    expect(store.board()?.equipment[0].values['plots']).toBe(7);
    expect(store.board()?.equipment[0].health).toBe('FAILED');
    store.next();
    expect(store.at()).toBe(t(20));
    expect(store.board()?.lastEventId).toBe(13);
  });

  it('previousSeeksToThePreviousEvent', () => {
    openWith([failed(11, 10), heartbeat(12, 30)]);
    store.next();
    store.next();
    expect(store.at()).toBe(t(30));
    store.previous();
    const b = boardReq();
    expect(b.request.params.get('at')).toBe(iso(t(10)));
    b.flush(boardAt({ at: iso(t(10)) }, { lastEventId: 11 }));
    expect(eventsReq().request.params.get('after')).toBe('11');
    expect(store.at()).toBe(t(10));
  });

  it('aSupervisorStartedMessageReloadsTheBoardAtThatInstant', () => {
    openWith([started(11, 30), failed(12, 40)]);
    store.play();
    vi.advanceTimersByTime(31_000);
    const b = boardReq();
    expect(b.request.params.get('at')).toBe(iso(t(30)));
    b.flush(boardAt({ at: iso(t(30)) }, { lastEventId: 11 }));
    expect(eventsReq().request.params.get('after')).toBe('11');
    expect(store.at()).toBe(t(30));
  });

  it('aGapLongerThanTheThresholdTurnsNoDataOn', () => {
    openWith([heartbeat(11, 10)], false);
    store.play();
    vi.advanceTimersByTime(99_000);
    expect(store.noData()).toBe(false);
    vi.advanceTimersByTime(1000);
    expect(store.at()).toBe(t(100));
    expect(store.noData()).toBe(true);
  });

  it('aStopTurnsNoDataOnAtOnce', () => {
    openWith([stopping(11, 10)]);
    store.play();
    vi.advanceTimersByTime(10_500);
    expect(store.noData()).toBe(true);
  });

  it('noDataAfterASeekIsTheBoardsValue', () => {
    openWith([], false, FROM, { noData: true });
    expect(store.noData()).toBe(true);
  });

  it('samplesUpdateValuesAndSampledAt', () => {
    openWith([sample(11, 5, 7)]);
    store.next();
    const unit = store.board()!.equipment[0];
    expect(unit.values['plots']).toBe(7);
    expect(unit.sampledAt).toBe(iso(t(5)));
  });

  it('theTickerKeepsTheLastTwentyNewestFirst', () => {
    const events: ReplayEventDto[] = [];
    for (let i = 0; i < 25; i++) {
      events.push(heartbeat(11 + i, 1 + i));
    }
    events.push(sample(40, 26, 1));
    openWith(events);
    store.setSpeed(60);
    store.play();
    vi.advanceTimersByTime(1000);
    const ticker = store.ticker();
    expect(ticker).toHaveLength(20);
    expect(ticker[0]).toEqual({ at: t(25), summary: 'event 35' });
    expect(ticker[19].summary).toBe('event 16');
  });

  it('aCommandReachesTheTickerOnly', () => {
    openWith([ev(11, 5, 'command', { equipmentId: 'RAD-N' }, 'COMMAND_ISSUED', 'ack by x')]);
    const before = store.board();
    store.next();
    expect(store.ticker().map(x => x.summary)).toEqual(['ack by x']);
    expect(store.board()).toBe(before);
  });

  it('liveStreamMessagesChangeNothingInReplay', () => {
    const live = new Subject<StreamMessage>();
    TestBed.resetTestingModule();
    vi.useFakeTimers({ now: FROM });
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ReplayStore, LiveBoardStore,
        { provide: BoardApi, useValue: { board: () => of(board({ lastEventId: 500 })) } },
        { provide: LiveStream, useValue: { connect: (): Observable<StreamMessage> => live } }],
    });
    const replay = TestBed.inject(ReplayStore);
    const liveStore = TestBed.inject(LiveBoardStore);
    const h = TestBed.inject(HttpTestingController);
    replay.open(FROM, TO, FROM);
    h.expectOne(r => r.url === '/api/v1/history/board').flush(boardAt());
    h.expectOne(r => r.url === '/api/v1/history/events').flush({ events: [], more: false });
    const before = replay.board();

    liveStore.start();
    live.next({ kind: 'open' });
    live.next({ kind: 'equipment', id: 501, data: eq('RAD-N', { health: 'FAILED' }) });
    live.next({ kind: 'alarm', id: 502, data: { id: 'a', equipmentId: 'RAD-N' } as never });
    live.next({
      kind: 'telemetry',
      data: { equipmentId: 'RAD-N', lastPollAt: iso(FROM), consecutiveFailures: 0, values: { plots: 9 }, tables: {}, serverTime: iso(FROM) },
    });
    expect(liveStore.board()?.lastEventId).toBe(502);
    expect(replay.board()).toBe(before);
    expect(replay.board()?.equipment[0].values['plots']).toBe(1);
    liveStore.stop();
    replay.pause();
  });
});
