import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ReplayBoardDto, ReplayEventsDto } from './models';

/** Reads the recorded history. It is separate from the live API on purpose and injects only {@code HttpClient}. */
@Injectable({ providedIn: 'root' })
export class ReplayApi {
  private readonly http = inject(HttpClient);

  /** The board as it was at {@code at} (ISO-8601 UTC). */
  boardAt(at: string): Observable<ReplayBoardDto> {
    return this.http.get<ReplayBoardDto>('/api/v1/history/board', { params: new HttpParams().set('at', at) });
  }

  /** Events after {@code after}, in id order, up to {@code until} (ISO-8601 UTC) when given; {@code limit} is 1 to 1,000. */
  events(after: number, until: string | null, limit: number): Observable<ReplayEventsDto> {
    let params = new HttpParams().set('after', after).set('limit', limit);
    if (until !== null) {
      params = params.set('until', until);
    }
    return this.http.get<ReplayEventsDto>('/api/v1/history/events', { params });
  }
}
