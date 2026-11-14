import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { EquipmentDto } from '../../../core/api/models';
import { BoardSource } from '../../../core/state/board-source';
import { EquipmentTile } from '../equipment-tile/equipment-tile';

const GROUPS = [
  { key: 'SURVEILLANCE', label: 'SURVEILLANCE' },
  { key: 'FLIGHT_DATA', label: 'FLIGHT DATA' },
];

interface Cell {
  first: EquipmentDto;
  second: EquipmentDto | null;
}

/** One cell per tile or per A ⇄ B pair; the second member of a pair is not repeated. */
function cellsOf(items: EquipmentDto[]): Cell[] {
  const sorted = [...items].sort((a, b) => a.id.localeCompare(b.id));
  const placed = new Set<string>();
  const cells: Cell[] = [];
  for (const e of sorted) {
    if (placed.has(e.id)) {
      continue;
    }
    placed.add(e.id);
    const partner = e.pair ? sorted.find(x => x.id === e.pair && !placed.has(x.id)) : undefined;
    if (partner) {
      placed.add(partner.id);
    }
    cells.push({ first: e, second: partner ?? null });
  }
  return cells;
}

@Component({
  selector: 'app-equipment-board',
  imports: [EquipmentTile],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (g of groups(); track g.key) {
      <section>
        <h2>{{ g.label }}</h2>
        <div class="cells">
          @for (c of g.cells; track c.first.id) {
            @if (c.second; as second) {
              <div class="pair">
                <app-equipment-tile [equipment]="c.first" />
                <span class="link" aria-hidden="true">⇄</span>
                <app-equipment-tile [equipment]="second" />
              </div>
            } @else {
              <app-equipment-tile [equipment]="c.first" />
            }
          }
        </div>
      </section>
    }
  `,
  styles: `
    section { margin-bottom: 20px; }
    h2 { margin: 0 0 8px; font: 600 12px var(--font-ui); letter-spacing: 0.1em; color: var(--muted); }
    .cells { display: flex; flex-wrap: wrap; gap: 16px; align-items: flex-start; }
    .pair { display: flex; align-items: center; gap: 8px; }
    .link { color: var(--muted); font: 16px var(--font-mono); }
  `,
})
export class EquipmentBoard {
  private readonly source = inject(BoardSource);

  protected readonly groups = computed(() => {
    const equipment = this.source.board()?.equipment ?? [];
    return GROUPS
      .map(g => ({ ...g, cells: cellsOf(equipment.filter(e => e.group === g.key)) }))
      .filter(g => g.cells.length > 0);
  });
}
