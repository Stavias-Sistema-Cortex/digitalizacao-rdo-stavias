import { getCortexDb } from "./cortexDb";
import type { ProcessedEventRecord } from "./db.types";

export async function hasProcessedEvent(
  commitSeq: number,
): Promise<boolean> {
  const database = await getCortexDb();

  const existing = await database.get(
    "processed_events",
    commitSeq,
  );

  return existing !== undefined;
}

export async function putProcessedEvent(
  event: ProcessedEventRecord,
): Promise<void> {
  const database = await getCortexDb();

  await database.put("processed_events", event);
}

export async function listProcessedEvents(): Promise<
  ProcessedEventRecord[]
> {
  const database = await getCortexDb();

  return database.getAll("processed_events");
}
