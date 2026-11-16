import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AckManyRequest, AckRequest, AckResultDto, AlarmDto, BoardDto, EquipmentDetailDto } from './models';

/** The commands a supervisor may send to a unit. */
export type EquipmentCommand = 'MAINTENANCE_ON' | 'MAINTENANCE_OFF' | 'RESTART' | 'MAKE_ACTIVE';

@Injectable({ providedIn: 'root' })
export class BoardApi {
  private readonly http = inject(HttpClient);

  board(): Observable<BoardDto> {
    return this.http.get<BoardDto>('/api/v1/board');
  }

  command(id: string, command: EquipmentCommand): Observable<unknown> {
    return this.http.post(`/api/v1/equipment/${encodeURIComponent(id)}/commands`, { command });
  }

  equipment(id: string): Observable<EquipmentDetailDto> {
    return this.http.get<EquipmentDetailDto>(`/api/v1/equipment/${encodeURIComponent(id)}`);
  }

  ack(id: string, comment?: string): Observable<AlarmDto> {
    const body: AckRequest = { comment: comment ?? null };
    return this.http.post<AlarmDto>(`/api/v1/alarms/${encodeURIComponent(id)}/ack`, body);
  }

  ackMany(ids: string[], comment?: string): Observable<AckResultDto> {
    const body: AckManyRequest = { ids, comment: comment ?? null };
    return this.http.post<AckResultDto>('/api/v1/alarms/ack', body);
  }
}
