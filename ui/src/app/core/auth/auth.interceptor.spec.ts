import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { FakeAuth, authProvider } from '../../testing/render';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  let http: HttpClient;
  let ctl: HttpTestingController;
  let auth: FakeAuth;
  let navigate: ReturnType<typeof vi.spyOn>;
  let router: Router;

  beforeEach(() => {
    auth = new FakeAuth(['SUPERVISOR']);
    TestBed.configureTestingModule({
      providers: [provideRouter([]), authProvider(auth), provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    ctl = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  });
  afterEach(() => ctl.verify());

  it('a401FromTheApiEndsTheSession', () => {
    http.get('/api/v1/board').subscribe({ error: () => undefined });
    ctl.expectOne('/api/v1/board').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.user()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/signin'], { queryParams: { expired: 1, returnUrl: '/' } });
  });

  it('a401ComesBackToThePageItInterrupted', () => {
    vi.spyOn(router, 'url', 'get').mockReturnValue('/history');
    http.get('/api/v1/alarms').subscribe({ error: () => undefined });
    ctl.expectOne('/api/v1/alarms').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(navigate).toHaveBeenCalledWith(['/signin'], { queryParams: { expired: 1, returnUrl: '/history' } });
  });

  it('a401OnTheSignInScreenDoesNotReturnToIt', () => {
    vi.spyOn(router, 'url', 'get').mockReturnValue('/signin?returnUrl=%2Fhistory');
    http.get('/api/v1/board').subscribe({ error: () => undefined });
    ctl.expectOne('/api/v1/board').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(navigate).toHaveBeenCalledWith(['/signin'], { queryParams: { expired: 1 } });
  });

  it('a401FromSignInIsLeftToThePage', () => {
    http.post('/api/v1/auth/login', '').subscribe({ error: () => undefined });
    ctl.expectOne('/api/v1/auth/login').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(navigate).not.toHaveBeenCalled();
    expect(auth.user()).not.toBeNull();
  });
});
