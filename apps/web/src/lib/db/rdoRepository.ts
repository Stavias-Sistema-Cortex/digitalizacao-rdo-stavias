import { getCortexDb } from "./cortexDb";
import type {
  LocalRdoRecord,
  LocalSyncStatus,
} from "./db.types";

export async function getLocalRdo(
  id: string,
): Promise<LocalRdoRecord | undefined> {
  const database = await getCortexDb();

  return database.get("rdos", id);
}

export async function listLocalRdos(): Promise<
  LocalRdoRecord[]
> {
  const database = await getCortexDb();

  const records = await database.getAllFromIndex(
    "rdos",
    "by-updated-at",
  );

  return records.reverse();
}

export async function listLocalRdosBySyncStatus(
  status: LocalSyncStatus,
): Promise<LocalRdoRecord[]> {
  const database = await getCortexDb();

  return database.getAllFromIndex(
    "rdos",
    "by-sync-status",
    status,
  );
}

export async function putLocalRdo(
  record: LocalRdoRecord,
): Promise<void> {
  const database = await getCortexDb();

  await database.put("rdos", record);
}

export async function deleteLocalRdo(
  id: string,
): Promise<void> {
  const database = await getCortexDb();

  await database.delete("rdos", id);
}
