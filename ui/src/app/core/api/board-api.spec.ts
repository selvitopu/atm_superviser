import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { BoardApi } from './board-api';

describe('BoardApi', () => {
  let api: BoardApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(BoardApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('fetches the board', () => {
    api.board().subscribe();
    expect(http.expectOne('/api/v1/board').request.method).toBe('GET');
  });

  it('fetches equipment detail', () => {
    api.equipment('RAD-N').subscribe();
    expect(http.expectOne('/api/v1/equipment/RAD-N').request.method).toBe('GET');
  });

  it('acknowledges one alarm with a comment', () => {
    api.ack('a1', 'seen').subscribe();
    const req = http.expectOne('/api/v1/alarms/a1/ack');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ comment: 'seen' });
  });

  it('acknowledges many alarms', () => {
    api.ackMany(['a1', 'a2']).subscribe();
    const req = http.expectOne('/api/v1/alarms/ack');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ ids: ['a1', 'a2'], comment: null });
  });
});
