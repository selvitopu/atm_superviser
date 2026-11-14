import { Signal } from '@angular/core';
import { BoardDto } from '../api/models';

/** Where the board page gets its data: the live store now, a replay store in v0.3. */
export abstract class BoardSource {
  abstract readonly board: Signal<BoardDto | null>;
}
