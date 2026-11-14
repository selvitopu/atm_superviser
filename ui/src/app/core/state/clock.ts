import { DestroyRef, Injectable, inject, signal } from '@angular/core';

/** A 1 s ticking clock that can be aligned to the server's time. */
@Injectable({ providedIn: 'root' })
export class Clock {
  private readonly tick = signal(Date.now());
  private offsetMs = 0;
  readonly now = this.tick.asReadonly();

  constructor() {
    const timer = setInterval(() => this.tick.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  setServerTime(iso: string): void {
    this.offsetMs = Date.parse(iso) - Date.now();
  }

  serverNow(): number {
    return this.now() + this.offsetMs;
  }
}
