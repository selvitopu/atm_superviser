import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from './auth.service';

/** Lets a signed-in user through; anyone else goes to sign-in and comes back to the same page afterwards. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.user()) {
    return true;
  }
  return auth.me().pipe(
    map(() => true),
    catchError(() => of(router.createUrlTree(['/signin'], { queryParams: { returnUrl: state.url } }))),
  );
};
