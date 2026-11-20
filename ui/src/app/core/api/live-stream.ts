import { Injectable, InjectionToken, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { StreamMessage } from './models';

export const EVENT_SOURCE_FACTORY = new InjectionToken<(url: string) => EventSource>('EVENT_SOURCE_FACTORY', {
  providedIn: 'root',
  factory: () => (url: string) => new EventSource(url),
});

const DURABLE = ['equipment', 'service', 'alarm', 'supervisor', 'flood'] as const;
const VOLATILE = ['telemetry', 'heartbeat'] as const;
const INVALID = Symbol('invalid');

/**
 * The supervisor's SSE stream as an Observable. The source is closed on error and on unsubscribe, so the
 * browser's own auto-reconnect never runs: the store owns reconnecting.
 *
 * An event that cannot be read (data that is not JSON, or a durable event without a positive numeric id) is never
 * dropped silently: the source is closed and `error` is emitted, so the store refetches the board and reconnects.
 */
@Injectable({ providedIn: 'root' })
export class LiveStream {
  private readonly factory = inject(EVENT_SOURCE_FACTORY);

  connect(lastEventId: number): Observable<StreamMessage> {
    return new Observable<StreamMessage>(subscriber => {
      const source = this.factory('/api/v1/stream?lastEventId=' + lastEventId);
      const emit = (m: StreamMessage) => subscriber.next(m);
      const fail = () => {
        source.close();
        emit({ kind: 'error' });
      };
      const parse = (e: MessageEvent): unknown => {
        try {
          return JSON.parse(e.data as string);
        } catch {
          return INVALID;
        }
      };

      source.onopen = () => emit({ kind: 'open' });
      source.onerror = fail;
      for (const kind of DURABLE) {
        source.addEventListener(kind, e => {
          const me = e as MessageEvent;
          const id = Number(me.lastEventId);
          const data = parse(me);
          if (!(Number.isFinite(id) && id > 0) || data === INVALID) {
            fail();
            return;
          }
          emit({ kind, id, data } as StreamMessage);
        });
      }
      for (const kind of VOLATILE) {
        source.addEventListener(kind, e => {
          const data = parse(e as MessageEvent);
          if (data === INVALID) {
            fail();
            return;
          }
          emit({ kind, data } as StreamMessage);
        });
      }
      source.addEventListener('resync', () => emit({ kind: 'resync' }));

      return () => source.close();
    });
  }
}
