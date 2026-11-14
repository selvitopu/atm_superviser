import { TestBed } from '@angular/core/testing';
import { EVENT_SOURCE_FACTORY, LiveStream } from './live-stream';
import { StreamMessage } from './models';

class FakeEventSource {
  static last: FakeEventSource;
  closed = false;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private listeners = new Map<string, (e: MessageEvent) => void>();
  constructor(public url: string) { FakeEventSource.last = this; }
  addEventListener(name: string, fn: (e: MessageEvent) => void) { this.listeners.set(name, fn); }
  close() { this.closed = true; }
  fire(name: string, data: string, lastEventId = '') {
    this.listeners.get(name)!({ data, lastEventId } as MessageEvent);
  }
}

describe('LiveStream', () => {
  let stream: LiveStream;
  let received: StreamMessage[];

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: EVENT_SOURCE_FACTORY, useValue: (url: string) => new FakeEventSource(url) }],
    });
    stream = TestBed.inject(LiveStream);
    received = [];
  });

  it('opens the stream from the last event id and emits open', () => {
    stream.connect(42).subscribe(m => received.push(m));
    expect(FakeEventSource.last.url).toBe('/api/v1/stream?lastEventId=42');
    FakeEventSource.last.onopen!();
    expect(received).toEqual([{ kind: 'open' }]);
  });

  it('maps durable events with the id from lastEventId', () => {
    stream.connect(1).subscribe(m => received.push(m));
    FakeEventSource.last.fire('service', '{"id":"SURV","status":"FAILED"}', '77');
    FakeEventSource.last.fire('supervisor', '{"type":"SUPERVISOR_STARTED","occurredAt":"2026-11-13T14:00:00Z"}', '78');
    expect(received).toEqual([
      { kind: 'service', id: 77, data: { id: 'SURV', status: 'FAILED' } },
      { kind: 'supervisor', id: 78, data: { type: 'SUPERVISOR_STARTED', occurredAt: '2026-11-13T14:00:00Z' } },
    ] as unknown as StreamMessage[]);
  });

  it('maps volatile events without an id', () => {
    stream.connect(1).subscribe(m => received.push(m));
    FakeEventSource.last.fire('heartbeat', '{"serverTime":"2026-11-13T14:00:00Z"}');
    FakeEventSource.last.fire('resync', '{}');
    expect(received).toEqual([
      { kind: 'heartbeat', data: { serverTime: '2026-11-13T14:00:00Z' } },
      { kind: 'resync' },
    ]);
  });

  it('emits error and closes the source on error', () => {
    stream.connect(1).subscribe(m => received.push(m));
    const source = FakeEventSource.last;
    source.onerror!();
    expect(received).toEqual([{ kind: 'error' }]);
    expect(source.closed).toBe(true);
  });

  it('closes the source on unsubscribe', () => {
    const sub = stream.connect(1).subscribe();
    const source = FakeEventSource.last;
    sub.unsubscribe();
    expect(source.closed).toBe(true);
  });
});
