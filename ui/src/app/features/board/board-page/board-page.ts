import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs';
import { LiveBoardStore } from '../../../core/state/live-board-store';
import { AlarmList } from '../../alarms/alarm-list/alarm-list';
import { AppHeader } from '../app-header/app-header';
import { DataNotLiveOverlay } from '../data-not-live-overlay/data-not-live-overlay';
import { DetailPanel } from '../detail-panel/detail-panel';
import { EquipmentBoard } from '../equipment-board/equipment-board';
import { ServiceStrip } from '../service-strip/service-strip';

@Component({
  selector: 'app-board-page',
  imports: [AppHeader, ServiceStrip, EquipmentBoard, DetailPanel, AlarmList, DataNotLiveOverlay],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-app-header class="header" />
    <app-service-strip class="services" />
    <main class="board"><app-equipment-board /></main>
    <aside class="detail"><app-detail-panel [equipmentId]="equipmentId()" /></aside>
    <app-alarm-list class="alarms" />
    <app-data-not-live-overlay />
  `,
  styles: `
    :host { position: relative; display: grid; min-width: 1280px; height: 100vh;
      grid-template-columns: 1fr 360px; grid-template-rows: auto auto 1fr auto;
      grid-template-areas: 'header header' 'services services' 'board detail' 'alarms alarms'; }
    .header { grid-area: header; }
    .services { grid-area: services; }
    .board { grid-area: board; padding: 8px 16px; overflow: auto; }
    .detail { grid-area: detail; overflow: auto; background: var(--panel); border-left: 1px solid var(--panel-2); }
    .alarms { grid-area: alarms; }
  `,
})
export class BoardPage {
  private readonly store = inject(LiveBoardStore);

  protected readonly equipmentId = toSignal(
    inject(ActivatedRoute).paramMap.pipe(map(p => p.get('equipmentId'))), { initialValue: null });

  constructor() {
    this.store.start();
    inject(DestroyRef).onDestroy(() => this.store.stop());
  }
}
