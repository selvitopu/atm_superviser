import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ImpactDto } from './models';

/** The words for the three verdicts; none of them reads as a clearance. */
export const VERDICT_LABEL: Record<string, string> = {
  NO_SERVICE_IMPACT: 'NO SERVICE IMPACT',
  REDUCED_MARGIN: 'REDUCED MARGIN',
  SERVICE_LOSS: 'SERVICE LOSS',
};

@Injectable({ providedIn: 'root' })
export class ImpactApi {
  private readonly http = inject(HttpClient);

  /** What taking these units into maintenance would do to the services; one {@code maintenance} parameter per unit. */
  assess(units: readonly string[]): Observable<ImpactDto> {
    let params = new HttpParams();
    for (const unit of units) {
      params = params.append('maintenance', unit);
    }
    return this.http.get<ImpactDto>('/api/v1/impact', { params });
  }
}
