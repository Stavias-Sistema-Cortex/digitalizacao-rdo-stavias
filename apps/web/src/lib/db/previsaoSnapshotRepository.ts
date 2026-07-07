import { getCortexDb } from "./cortexDb";
import type { PrevisaoSnapshotRecord } from "./db.types";

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
): Promise<void> {
  const database = await getCortexDb();
  await database.put("previsao_snapshots", record);
}
