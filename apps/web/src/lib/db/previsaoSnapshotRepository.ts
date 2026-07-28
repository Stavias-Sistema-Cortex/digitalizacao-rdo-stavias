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
