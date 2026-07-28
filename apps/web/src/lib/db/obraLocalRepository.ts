import { getCortexDb } from "./cortexDb";
import {
  mergeObraRecords,
} from "./homeRecordMappers";
import type { ObraLocalRecord } from "./db.types";

export async function listObrasLocais(): Promise<
  ObraLocalRecord[]
> {
  const database = await getCortexDb();
  return database.getAll("obras");
}

export async function getObraLocal(
  id: string,
): Promise<ObraLocalRecord | undefined> {
  const database = await getCortexDb();
  return database.get("obras", id);
}

export async function mergeObraLocal(
  record: ObraLocalRecord,
): Promise<void> {
  const database = await getCortexDb();
  const existing = await database.get("obras", record.id);
  const normalized: ObraLocalRecord = {
    ...record,
    versaoEntidade: record.versaoEntidade ?? null,
    arquivadoEm: record.arquivadoEm ?? null,
    syncStatus: record.syncStatus ?? "SYNCED",
    ultimoErro: record.ultimoErro ?? null,
  };
  await database.put("obras", mergeObraRecords(existing, normalized));
}
