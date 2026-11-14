import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { BoardSource } from './core/state/board-source';
import { LiveBoardStore } from './core/state/live-board-store';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(),
    provideRouter(routes),
    LiveBoardStore,
    { provide: BoardSource, useExisting: LiveBoardStore },
  ]
};
