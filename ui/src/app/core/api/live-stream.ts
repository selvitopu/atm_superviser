import { Injectable, InjectionToken, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { StreamMessage } from './models';

export const EVENT_SOURCE_FACTORY = new InjectionToken<(url: string) => EventSource>('EVENT_SOURCE_FACTORY', {
  providedIn: 'root',
  factory: () => (url: string) => new EventSource(url),
});

const DURABLE = ['equipment', 'service', 'alarm', 'supervisor'] as const;

/**
 * The supervisor's SSE stream as an Observable. The source is closed on error and on unsubscribe, so the
 * browser's own auto-reconnect never runs: the store owns reconnecting.
 */
@Injectable({ providedIn: 'root' })
export class LiveStream {
  private readonly factory = inject(EVENT_SOURCE_FACTORY);

  connect(lastEventId: number): Observable<StreamMessage> {
    return new Observable<StreamMessage>(subscriber => {
      const source = this.factory('/api/v1/stream?lastEventId=' + lastEventId);
      const emit = (m: StreamMessage) => subscriber.next(m);
      const parse = (e: MessageEvent): unknown => JSON.parse(e.data as string);

      source.onopen = () => emit({ kind: 'open' });
      source.onerror = () => {
        source.close();
        emit({ kind: 'error' });
      };
      for (const kind of DURABLE) {
        source.addEventListener(kind, e => {
          const me = e as MessageEvent;
          emit({ kind, id: Number(me.lastEventId), data: parse(me) } as StreamMessage);
        });
      }
      source.addEventListener('telemetry', e => emit({ kind: 'telemetry', data: parse(e as MessageEvent) } as StreamMessage));
      source.addEventListener('heartbeat', e => emit({ kind: 'heartbeat', data: parse(e as MessageEvent) } as StreamMessage));
      source.addEventListener('resync', () => emit({ kind: 'resync' }));

      return () => source.close();
    });
  }
}
