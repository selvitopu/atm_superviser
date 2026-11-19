import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * Lets a route through in demo mode only, and sends anyone else to the board: the simulator does not exist in a real
 * centre. After a reload or a typed URL the sign-in configuration is not known yet, so it is read first.
 */
export const demoGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const config = auth.config();
  const known = config ? of(config) : auth.loadConfig();
  return known.pipe(
    map(c => c.demoMode ? true : router.createUrlTree(['/board'])),
    catchError(() => of(router.createUrlTree(['/board']))),
  );
};
