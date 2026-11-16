import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface AdminUserDto {
  username: string;
  displayName: string;
  role: string;
  enabled: boolean;
}

/** Account administration: administrators only. Every change ends that user's live sessions on the server. */
@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);

  users(): Observable<AdminUserDto[]> {
    return this.http.get<AdminUserDto[]>('/api/v1/admin/users');
  }

  change(username: string, change: { role?: string; enabled?: boolean }): Observable<AdminUserDto> {
    return this.http.patch<AdminUserDto>(`/api/v1/admin/users/${encodeURIComponent(username)}`, change);
  }

  setPassword(username: string, password: string): Observable<void> {
    return this.http.post<void>(`/api/v1/admin/users/${encodeURIComponent(username)}/password`, { password });
  }
}
