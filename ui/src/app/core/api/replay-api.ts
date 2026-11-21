import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { RaisedDto, ReplayBoardDto, ReplayEventsDto, TimelineDto } from './models';

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

  /** Markers, flood spans, gap spans and restarts of {@code [from, to]} (ISO-8601 UTC, at most 7 days). */
  timeline(from: string, to: string): Observable<TimelineDto> {
    return this.http.get<TimelineDto>('/api/v1/history/timeline', { params: new HttpParams().set('from', from).set('to', to) });
  }

  /** When the newest raise of this alarm was recorded; a 404 when none was. */
  raised(alarmId: string): Observable<RaisedDto> {
    return this.http.get<RaisedDto>(`/api/v1/history/alarms/${encodeURIComponent(alarmId)}/raised`);
  }
}
