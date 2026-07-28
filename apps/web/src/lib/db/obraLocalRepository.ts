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
import { guardSyncTransaction } from "../sync/guardedSyncTransaction";

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

  if (guard) {
    const guardedTransaction = guardSyncTransaction(
      database.transaction("obras", "readwrite"),
      guard,
    );
    const store = guardedTransaction.transaction.objectStore(
      "obras",
    );
    try {
      const existing = await store.get(record.id);
      await store.put(mergedObraForStorage(existing, record));
    } catch (error: unknown) {
      await guardedTransaction.complete();
      throw error;
    }
    await guardedTransaction.complete();
    return;
  }

  const existing = await database.get("obras", record.id);
  await database.put(
    "obras",
    mergedObraForStorage(existing, record),
  );
}

function mergedObraForStorage(
  existing: ObraLocalRecord | undefined,
  record: ObraLocalRecord,
): ObraLocalRecord {
  const merged = mergeObraRecords(existing, record);
  return {
    ...merged,
    versaoEntidade: merged.versaoEntidade ?? null,
    arquivadoEm: merged.arquivadoEm ?? null,
    syncStatus: merged.syncStatus ?? "SYNCED",
    ultimoErro: merged.ultimoErro ?? null,
  };
}
