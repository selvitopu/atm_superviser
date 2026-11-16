import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService, RoleName, roleAllows } from './auth.service';

/**
 * Lets through a user whose role includes {@code role}; anyone else goes back to the board. After a reload the user is
 * not known yet, so it asks the server first. The server refuses the same actions: this only keeps the screen honest.
 */
export function roleGuard(role: RoleName): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    const user = auth.user();
    const known = user ? of(user) : auth.me();
    return known.pipe(
      map(u => roleAllows(u.roles, role) ? true : router.createUrlTree(['/board'])),
      catchError(() => of(router.createUrlTree(['/signin']))),
    );
  };
}
