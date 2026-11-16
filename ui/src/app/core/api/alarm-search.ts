import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AlarmDto, PageDto } from './models';

/** A search of the alarm history; empty filters are left out. {@code from} and {@code to} are ISO UTC instants. */
export interface AlarmQuery {
  state: 'open' | 'all';
  severity: string;
  equipment: string;
  from: string;
  to: string;
  page: number;
  size: number;
}

@Injectable({ providedIn: 'root' })
export class AlarmSearch {
  private readonly http = inject(HttpClient);

  search(q: AlarmQuery): Observable<PageDto<AlarmDto>> {
    let params = new HttpParams().set('state', q.state);
    for (const key of ['severity', 'equipment', 'from', 'to'] as const) {
      if (q[key]) {
        params = params.set(key, q[key]);
      }
    }
    params = params.set('page', q.page).set('size', q.size);
    return this.http.get<PageDto<AlarmDto>>('/api/v1/alarms', { params });
  }
}
