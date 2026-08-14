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

/**
 * Funde uma lista inteira numa transação só.
 *
 * <p>A hidratação da Home chamava {@link mergeObraLocal} dentro de um laço, e
 * cada chamada abria transação própria, lia, gravava e — o caro — esperava o
 * commit antes da próxima começar. Com trinta obras eram trinta commits em
 * série; o custo não é a leitura nem a escrita, é a espera enfileirada. Cada
 * transação guardada ainda registrava e removia um ouvinte de `window`, então o
 * laço também pagava trinta idas ao registro de eventos.
 *
 * <p>A ordem da lista é preservada de propósito: obra repetida na mesma remessa
 * funde na sequência em que veio, exatamente como no laço anterior. E o
 * `assertSyncSession` continua a cada item, porque é verificação síncrona e
 * barata — o que sai é o commit por item, não a guarda por item.
 */
export async function mergeObrasLocais(
  records: readonly ObraLocalRecord[],
  guard?: SyncSessionGuard,
): Promise<void> {
  if (records.length === 0) return;
  if (guard) assertSyncSession(guard);
  const database = await getCortexDb();
  if (guard) assertSyncSession(guard);

  if (!guard) {
    const transaction = database.transaction("obras", "readwrite");
    const store = transaction.objectStore("obras");
    for (const record of records) {
      const existing = await store.get(record.id);
      await store.put(mergedObraForStorage(existing, record));
    }
    await transaction.done;
    return;
  }

  const guardedTransaction = guardSyncTransaction(
    database.transaction("obras", "readwrite"),
    guard,
  );
  const store = guardedTransaction.transaction.objectStore("obras");
  try {
    for (const record of records) {
      assertSyncSession(guard);
      const existing = await store.get(record.id);
      await store.put(mergedObraForStorage(existing, record));
    }
  } catch (error: unknown) {
    await guardedTransaction.complete();
    throw error;
  }
  await guardedTransaction.complete();
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
