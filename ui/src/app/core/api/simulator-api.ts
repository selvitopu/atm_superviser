import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** One simulated device as the simulator console shows it. */
export interface SimDeviceDto {
  id: string;
  type: string;
  status: string;
  role: string;
  maintenance: boolean;
  faults: string[];
  responding: boolean;
  dropTraps: boolean;
}

/** The simulator console, through the supervisor: demo mode and supervisors only. */
@Injectable({ providedIn: 'root' })
export class SimulatorApi {
  private readonly http = inject(HttpClient);

  devices(): Observable<SimDeviceDto[]> {
    return this.http.get<SimDeviceDto[]>('/api/v1/simulator/devices');
  }

  fault(id: string, fault: string, target: string | null = null): Observable<SimDeviceDto> {
    return this.http.post<SimDeviceDto>(`/api/v1/simulator/devices/${encodeURIComponent(id)}/faults`, { fault, target });
  }

  reset(): Observable<void> {
    return this.http.post<void>('/api/v1/simulator/reset', null);
  }
}
