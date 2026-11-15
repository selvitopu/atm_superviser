import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * A 401 from the API means the session has ended (it expired, or the supervisor restarted): forget the user and go to
 * sign-in, instead of reconnecting forever behind a stale board. The sign-in endpoints answer their own 401s.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return next(request).pipe(catchError((error: unknown) => {
    if (error instanceof HttpErrorResponse && error.status === 401
        && request.url.startsWith('/api/') && !request.url.startsWith('/api/v1/auth/')) {
      auth.sessionEnded();
      void router.navigate(['/signin'], { queryParams: { expired: 1 } });
    }
    return throwError(() => error);
  }));
};
