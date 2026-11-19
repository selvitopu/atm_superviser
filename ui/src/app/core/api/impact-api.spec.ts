import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ImpactApi } from './impact-api';

describe('ImpactApi', () => {
  it('sendsOneParameterPerUnit', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(ImpactApi).assess(['FDPS-B', 'RAD-N']).subscribe();
    const req = TestBed.inject(HttpTestingController).expectOne(r => r.url === '/api/v1/impact');
    expect(req.request.urlWithParams).toBe('/api/v1/impact?maintenance=FDPS-B&maintenance=RAD-N');
  });
});
