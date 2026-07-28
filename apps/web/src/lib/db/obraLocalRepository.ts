import { getCortexDb } from "./cortexDb";
import {
  mergeObraRecords,
} from "./homeRecordMappers";
import type { ObraLocalRecord } from "./db.types";
import { filterOperationalObras } from "./obraSelectors";
import {
  assertSyncSession,
  type SyncSessionGuard,
} from "../sync/syncSession";

export { filterOperationalObras } from "./obraSelectors";

export async function listObrasLocais(
  options: { includeArchived?: boolean } = {},
): Promise<
  ObraLocalRecord[]
> {
  const database = await getCortexDb();
  const cached = await database.getAll("obras");
  return options.includeArchived
    ? cached
    : filterOperationalObras(cached);
}

export async function getObraLocal(
  id: string,
): Promise<ObraLocalRecord | undefined> {
  const database = await getCortexDb();
  return database.get("obras", id);
}

export async function mergeObraLocal(
  record: ObraLocalRecord,
  guard?: SyncSessionGuard,
): Promise<void> {
  if (guard) assertSyncSession(guard);
  const database = await getCortexDb();
  if (guard) assertSyncSession(guard);
  const existing = await database.get("obras", record.id);
  if (guard) assertSyncSession(guard);
  const merged = mergeObraRecords(existing, record);
  await database.put("obras", {
    ...merged,
    versaoEntidade: merged.versaoEntidade ?? null,
    arquivadoEm: merged.arquivadoEm ?? null,
    syncStatus: merged.syncStatus ?? "SYNCED",
    ultimoErro: merged.ultimoErro ?? null,
  });
  if (guard) assertSyncSession(guard);
}
