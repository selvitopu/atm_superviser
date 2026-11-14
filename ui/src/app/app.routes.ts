import { Routes } from '@angular/router';
import { BoardPage } from './features/board/board-page/board-page';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'board' },
  { path: 'board', component: BoardPage },
  { path: 'board/:equipmentId', component: BoardPage },
];
