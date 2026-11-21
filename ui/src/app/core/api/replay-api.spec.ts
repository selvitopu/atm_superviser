import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ReplayApi } from './replay-api';

describe('ReplayApi', () => {
  function setup() {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    return { api: TestBed.inject(ReplayApi), http: TestBed.inject(HttpTestingController) };
  }

  it('asksForTheBoardAtAnInstant', () => {
    const { api, http } = setup();
    api.boardAt('2026-11-13T14:00:00Z').subscribe();
    const req = http.expectOne(r => r.url === '/api/v1/history/board');
    expect(req.request.urlWithParams).toBe('/api/v1/history/board?at=2026-11-13T14:00:00Z');
  });

  it('pagesEventsAfterAnIdUpToTheWindowEnd', () => {
    const { api, http } = setup();
    api.events(42, '2026-11-13T15:00:00Z', 500).subscribe();
    const req = http.expectOne(r => r.url === '/api/v1/history/events');
    expect(req.request.urlWithParams).toBe('/api/v1/history/events?after=42&limit=500&until=2026-11-13T15:00:00Z');
  });

  it('omitsUntilWhenThereIsNone', () => {
    const { api, http } = setup();
    api.events(0, null, 10).subscribe();
    expect(http.expectOne(r => r.url === '/api/v1/history/events').request.urlWithParams)
      .toBe('/api/v1/history/events?after=0&limit=10');
  });
});
