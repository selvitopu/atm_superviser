import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map, startWith } from 'rxjs';
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
    <app-app-header class="header" [live]="true" />
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
  private readonly router = inject(Router);

  /** The page is one reused parent route; the selected unit is the param of its child route. */
  protected readonly equipmentId = toSignal(
    this.router.events.pipe(
      filter(e => e instanceof NavigationEnd),
      startWith(null),
      map(() => this.selectedId()),
    ),
    { initialValue: null });

  private selectedId(): string | null {
    let route = this.router.routerState.snapshot.root;
    while (route.firstChild) {
      route = route.firstChild;
    }
    return route.paramMap.get('equipmentId');
  }

  constructor() {
    this.store.start();
    inject(DestroyRef).onDestroy(() => this.store.stop());
  }
}
