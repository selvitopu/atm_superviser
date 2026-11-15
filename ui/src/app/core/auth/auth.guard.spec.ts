import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';
import { FakeAuth, authProvider } from '../../testing/render';
import { authGuard } from './auth.guard';

describe('authGuard', () => {
  function run(auth: FakeAuth, url = '/board/RAD-N') {
    TestBed.configureTestingModule({ providers: [provideRouter([]), authProvider(auth)] });
    return TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot));
  }

  it('anUnknownSessionRedirectsToSignIn', async () => {
    const result = await firstValueFrom(run(new FakeAuth([])) as Observable<boolean | UrlTree>);
    expect(result instanceof UrlTree).toBe(true);
    expect(result.toString()).toBe('/signin?returnUrl=%2Fboard%2FRAD-N');
  });

  it('aSignedInUserPasses', () => {
    expect(run(new FakeAuth(['VIEWER']))).toBe(true);
  });
});
