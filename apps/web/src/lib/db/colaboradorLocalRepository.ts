import { getCortexDb } from "./cortexDb";
import type { ColaboradorLocalRecord } from "./db.types";

export async function listColaboradoresLocais(): Promise<
  ColaboradorLocalRecord[]
> {
  const database = await getCortexDb();
  return database.getAll("colaboradores");
}

export async function mergeColaboradoresLocais(
  records: ColaboradorLocalRecord[],
): Promise<void> {
  if (records.length === 0) {
    return;
  }

  const database = await getCortexDb();
  const transaction = database.transaction(
    "colaboradores",
    "readwrite",
  );
  const store = transaction.objectStore("colaboradores");

  for (const record of records) {
    await store.put(record);
  }

  await transaction.done;
}
