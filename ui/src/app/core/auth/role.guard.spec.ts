import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { Observable, firstValueFrom, isObservable, of } from 'rxjs';
import { authProvider, FakeAuth } from '../../testing/render';
import { roleGuard } from './role.guard';

describe('roleGuard', () => {
  function run(role: 'SUPERVISOR' | 'ADMIN', auth: FakeAuth): Promise<boolean | UrlTree> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideRouter([]), authProvider(auth)] });
    const result = TestBed.runInInjectionContext(() => roleGuard(role)({} as never, { url: '/x' } as never));
    const value = isObservable(result) ? result as Observable<boolean | UrlTree> : of(result as boolean | UrlTree);
    return firstValueFrom(value);
  }

  const url = (r: boolean | UrlTree) => r instanceof UrlTree ? TestBed.inject(Router).serializeUrl(r) : r;

  it('letsAUserWithTheRoleThrough', async () => {
    expect(await run('ADMIN', new FakeAuth(['ADMIN']))).toBe(true);
    expect(await run('SUPERVISOR', new FakeAuth(['ADMIN']))).toBe(true); // the hierarchy, as on the server
  });

  it('redirectsWithoutTheRoleToTheBoard', async () => {
    expect(url(await run('ADMIN', new FakeAuth(['SUPERVISOR'])))).toBe('/board');
  });

  it('waitsForTheUserAfterAReload', async () => {
    const auth = new FakeAuth(['ADMIN']);
    const me = auth.me.bind(auth);
    const known = auth.user();
    auth.user.set(null); // a reload: the user is not known until /me answers
    auth.me = () => { auth.user.set(known); return me(); };
    expect(await run('ADMIN', auth)).toBe(true);
  });
});
