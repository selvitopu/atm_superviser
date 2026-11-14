import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { LiveBoardStore } from '../../../core/state/live-board-store';

@Component({
  selector: 'app-data-not-live-overlay',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (store.dataNotLive()) {
      <div class="overlay" role="status"><span>DATA NOT LIVE</span></div>
    }
  `,
  styles: `
    .overlay { position: absolute; inset: 0; z-index: 10; display: flex; align-items: center; justify-content: center;
      background: repeating-linear-gradient(135deg, rgb(11 15 20 / 0.72) 0 10px, rgb(24 32 43 / 0.72) 10px 20px); }
    span { padding: 8px 20px; background: var(--panel); border: 1px solid var(--degraded); color: var(--degraded);
      font: 700 16px var(--font-mono); letter-spacing: 0.1em; }
  `,
})
export class DataNotLiveOverlay {
  protected readonly store = inject(LiveBoardStore);
}
