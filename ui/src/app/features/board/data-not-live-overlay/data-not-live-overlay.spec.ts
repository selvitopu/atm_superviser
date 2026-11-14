import { FakeStore, render, storeProviders } from '../../../testing/render';
import { DataNotLiveOverlay } from './data-not-live-overlay';

describe('DataNotLiveOverlay', () => {
  it('overlay appears when data not live', () => {
    const store = new FakeStore();
    const f = render(DataNotLiveOverlay, {}, storeProviders(store));
    expect(f.text()).not.toContain('DATA NOT LIVE');
    store.dataNotLive.set(true);
    f.fixture.detectChanges();
    expect(f.text()).toContain('DATA NOT LIVE');
  });
});
