import type { ObraLocalRecord } from "./db.types";

export function filterOperationalObras(
  obras: readonly ObraLocalRecord[],
): ObraLocalRecord[] {
  return obras.filter((obra) => obra.arquivadoEm == null);
}
