import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { demoGuard } from './core/auth/demo.guard';
import { RegisterPage } from './features/auth/register-page/register-page';
import { SignInPage } from './features/auth/sign-in-page/sign-in-page';
import { BoardPage } from './features/board/board-page/board-page';
import { HistoryPage } from './features/history/history-page/history-page';
import { SimulatorPage } from './features/simulator/simulator-page/simulator-page';
import { UsersPage } from './features/admin/users-page/users-page';
import { roleGuard } from './core/auth/role.guard';

/** BoardPage is a single parent route so selecting equipment never recreates it (and never restarts the stream). */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'board' },
  { path: 'signin', component: SignInPage, title: 'Sign in · ATM Supervisor' },
  { path: 'register', component: RegisterPage, title: 'Create account · ATM Supervisor' },
  {
    path: 'board', component: BoardPage, canActivate: [authGuard], title: 'Board · ATM Supervisor',
    children: [{ path: '', children: [] }, { path: ':equipmentId', children: [] }],
  },
  { path: 'history', component: HistoryPage, canActivate: [authGuard], title: 'Alarm history · ATM Supervisor' },
  {
    path: 'simulator', component: SimulatorPage, canActivate: [authGuard, roleGuard('SUPERVISOR'), demoGuard],
    title: 'Simulator · ATM Supervisor',
  },
  {
    path: 'admin/users', component: UsersPage, canActivate: [authGuard, roleGuard('ADMIN')],
    title: 'Users · ATM Supervisor',
  },
  { path: '**', redirectTo: 'board' },
];
