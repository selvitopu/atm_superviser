import { TestBed } from '@angular/core/testing';
import { Router, UrlTree, provideRouter } from '@angular/router';
import { Observable, firstValueFrom, isObservable, of } from 'rxjs';
import { AuthConfigDto } from '../api/models';
import { authProvider, FakeAuth } from '../../testing/render';
import { demoGuard } from './demo.guard';

describe('demoGuard', () => {
  const config = (demoMode: boolean): AuthConfigDto => ({ demoMode, registrationEnabled: true, demoAccounts: [] });

  function run(auth: FakeAuth): Promise<boolean | UrlTree> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideRouter([]), authProvider(auth)] });
    const result = TestBed.runInInjectionContext(() => demoGuard({} as never, { url: '/simulator' } as never));
    const value = isObservable(result) ? result as Observable<boolean | UrlTree> : of(result as boolean | UrlTree);
    return firstValueFrom(value);
  }

  const url = (r: boolean | UrlTree) => r instanceof UrlTree ? TestBed.inject(Router).serializeUrl(r) : r;

  it('letsThroughInDemoMode', async () => {
    const auth = new FakeAuth(['SUPERVISOR']);
    auth.config.set(config(true));
    expect(await run(auth)).toBe(true);
  });

  it('sendsEveryoneToTheBoardOutsideDemoMode', async () => {
    const auth = new FakeAuth(['ADMIN']);
    auth.config.set(config(false));
    expect(url(await run(auth))).toBe('/board');
  });

  it('readsTheConfigurationAfterAReload', async () => {
    const auth = new FakeAuth(['SUPERVISOR']); // a typed or reloaded URL: nothing has read the configuration yet
    let loads = 0;
    auth.loadConfig = () => {
      loads++;
      auth.config.set(config(true));
      return of(config(true));
    };
    expect(await run(auth)).toBe(true);
    expect(loads).toBe(1);
  });
});
