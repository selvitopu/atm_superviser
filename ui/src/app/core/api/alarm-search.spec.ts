import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AlarmSearch } from './alarm-search';

describe('AlarmSearch', () => {
  it('sendsOnlyTheFiltersThatAreSet', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(AlarmSearch).search({ state: 'all', severity: '', equipment: 'RAD-N', from: '', to: '',
      page: 2, size: 50 }).subscribe();
    const req = TestBed.inject(HttpTestingController).expectOne(r => r.url === '/api/v1/alarms');
    expect(req.request.params.toString()).toBe('state=all&equipment=RAD-N&page=2&size=50');
  });
});
