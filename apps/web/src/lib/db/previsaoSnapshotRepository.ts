import { getCortexDb } from "./cortexDb";
import type { PrevisaoSnapshotRecord } from "./db.types";
import {
  assertSyncSession,
  type SyncSessionGuard,
} from "../sync/syncSession";
import { guardSyncTransaction } from "../sync/guardedSyncTransaction";

export async function listSnapshotsByObra(
  obraId: string,
): Promise<PrevisaoSnapshotRecord[]> {
  const database = await getCortexDb();
  return database.getAllFromIndex(
    "previsao_snapshots",
    "by-obra-id",
    obraId,
  );
}

export async function putPrevisaoSnapshot(
  record: PrevisaoSnapshotRecord,
  guard?: SyncSessionGuard,
): Promise<void> {
  if (guard) assertSyncSession(guard);
  const database = await getCortexDb();
  if (guard) assertSyncSession(guard);

  if (guard) {
    const guardedTransaction = guardSyncTransaction(
      database.transaction(
        "previsao_snapshots",
        "readwrite",
      ),
      guard,
    );
    const store = guardedTransaction.transaction.objectStore(
      "previsao_snapshots",
    );
    try {
      await store.put(record);
    } catch (error: unknown) {
      await guardedTransaction.complete();
      throw error;
    }
    await guardedTransaction.complete();
    return;
  }

  await database.put("previsao_snapshots", record);
}

/**
 * Grava o histórico inteiro de uma obra numa transação só.
 *
 * <p>Este era o pior dos laços da Home, e por um motivo de escala: obra não tem
 * um punhado de snapshots, tem um por data de referência. Meses de execução
 * viram centenas de commits em série ao abrir a aba — e o custo cresce com o
 * tempo de obra, então a tela fica mais lenta justamente onde há mais trabalho
 * registrado.
 */
export async function putPrevisaoSnapshots(
  records: readonly PrevisaoSnapshotRecord[],
  guard?: SyncSessionGuard,
): Promise<void> {
  if (records.length === 0) return;
  if (guard) assertSyncSession(guard);
  const database = await getCortexDb();
  if (guard) assertSyncSession(guard);

  if (!guard) {
    const transaction = database.transaction(
      "previsao_snapshots",
      "readwrite",
    );
    const store = transaction.objectStore("previsao_snapshots");
    for (const record of records) {
      await store.put(record);
    }
    await transaction.done;
    return;
  }

  const guardedTransaction = guardSyncTransaction(
    database.transaction("previsao_snapshots", "readwrite"),
    guard,
  );
  const store = guardedTransaction.transaction.objectStore(
    "previsao_snapshots",
  );
  try {
    for (const record of records) {
      assertSyncSession(guard);
      await store.put(record);
    }
  } catch (error: unknown) {
    await guardedTransaction.complete();
    throw error;
  }
  await guardedTransaction.complete();
}

/**
 * Troca atomicamente a projeção local de uma obra pelo recorte atual aceito.
 * A remessa pode ser vazia: isso é a confirmação de que nenhum snapshot
 * vigente deve sobreviver no aparelho.
 */
export async function replacePrevisaoSnapshotsForObra(
  obraId: string,
  records: readonly PrevisaoSnapshotRecord[],
  guard?: SyncSessionGuard,
): Promise<void> {
  if (records.some((record) => record.obraId !== obraId)) {
    throw new Error("O snapshot não pertence à obra hidratada.");
  }
  if (guard) assertSyncSession(guard);
  const database = await getCortexDb();
  if (guard) assertSyncSession(guard);

  const transaction = database.transaction(
    "previsao_snapshots",
    "readwrite",
  );
  const guarded = guard
    ? guardSyncTransaction(transaction, guard)
    : null;
  const store = guarded
    ? guarded.transaction.objectStore("previsao_snapshots")
    : transaction.objectStore("previsao_snapshots");

  try {
    const previousKeys = await store.index("by-obra-id").getAllKeys(obraId);
    for (const key of previousKeys) {
      if (guard) assertSyncSession(guard);
      await store.delete(key);
    }
    for (const record of records) {
      if (guard) assertSyncSession(guard);
      await store.put(record);
    }
  } catch (error: unknown) {
    if (guarded) {
      await guarded.complete();
    } else {
      await transaction.done.catch(() => undefined);
    }
    throw error;
  }

  if (guarded) {
    await guarded.complete();
  } else {
    await transaction.done;
  }
}
