import { Routes } from '@angular/router';
import { BoardPage } from './features/board/board-page/board-page';

/** BoardPage is a single parent route so selecting equipment never recreates it (and never restarts the stream). */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'board' },
  { path: 'board', component: BoardPage, children: [{ path: '', children: [] }, { path: ':equipmentId', children: [] }] },
];
