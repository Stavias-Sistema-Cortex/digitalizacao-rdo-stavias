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

export async function listLocalRdosByObra(
  obraId: string,
): Promise<LocalRdoRecord[]> {
  const database = await getCortexDb();

  return database.getAllFromIndex(
    "rdos",
    "by-obra-id",
    obraId,
  );
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

/**
 * Removes a local RDO that was never accepted by the server and whose create
 * mutation has been rejected terminally. This intentionally does not contact
 * the server: the remote rejection trace remains available for audit, while
 * the rejected local projection no longer appears as an operational RDO.
 */
export async function discardRejectedLocalRdo(
  id: string,
): Promise<void> {
  if (!id.trim()) {
    throw new Error("Informe o RDO local que deve ser descartado.");
  }

  const database = await getCortexDb();
  const transaction = database.transaction(
    [
      "rdos",
      "outbox_mutations",
      "operational_events",
      "rdo_attachments",
      "rdoMaoObra",
      "rdoEquipamentos",
      "rdoMateriais",
      "rdoControlesGeometricos",
    ],
    "readwrite",
  );

  try {
    const rdoStore = transaction.objectStore("rdos");
    const outboxStore = transaction.objectStore("outbox_mutations");
    const eventStore = transaction.objectStore("operational_events");
    const attachmentStore = transaction.objectStore("rdo_attachments");
    const maoObraStore = transaction.objectStore("rdoMaoObra");
    const equipamentoStore = transaction.objectStore("rdoEquipamentos");
    const materialStore = transaction.objectStore("rdoMateriais");
    const controleStore = transaction.objectStore(
      "rdoControlesGeometricos",
    );

    const [rdo, relatedMutations, allMutations] = await Promise.all([
      rdoStore.get(id),
      outboxStore.index("by-entity-id").getAll(id),
      outboxStore.getAll(),
    ]);
    const rdoMutations = relatedMutations.filter(
      (mutation) => mutation.entidadeTipo === "RDO",
    );
    const discardedMutationIds = new Set(
      rdoMutations.map((mutation) => mutation.clientMutationId),
    );
    const hasRejectedCreate = rdoMutations.some(
      (mutation) =>
        mutation.operacao === "CRIAR_RDO" &&
        mutation.status === "REJECTED",
    );
    const hasAnyNonRejectedMutation = rdoMutations.some(
      (mutation) => mutation.status !== "REJECTED",
    );
    const hasForeignMutationWithSameEntityId = relatedMutations.some(
      (mutation) => mutation.entidadeTipo !== "RDO",
    );
    const hasExternalDependentMutation = allMutations.some(
      (mutation) =>
        !discardedMutationIds.has(mutation.clientMutationId) &&
        (mutation.dependsOnMutationIds ?? []).some(
          (dependencyId) => discardedMutationIds.has(dependencyId),
        ),
    );

    if (
      !rdo ||
      rdo.syncStatus !== "ERROR" ||
      rdo.versaoEntidade !== null ||
      rdo.statusRdo === "ENVIADO" ||
      !hasRejectedCreate ||
      hasAnyNonRejectedMutation ||
      hasForeignMutationWithSameEntityId ||
      hasExternalDependentMutation
    ) {
      throw new Error(
        "Somente RDOs rejeitados definitivamente podem ser descartados localmente.",
      );
    }

    const [
      eventKeys,
      attachmentKeys,
      maoObraKeys,
      equipamentoKeys,
      materialKeys,
      controleKeys,
    ] = await Promise.all([
      eventStore.index("by-rdo-id").getAllKeys(id),
      attachmentStore.index("by-rdo-id").getAllKeys(id),
      maoObraStore.index("by-rdo-id").getAllKeys(id),
      equipamentoStore.index("by-rdo-id").getAllKeys(id),
      materialStore.index("by-rdo-id").getAllKeys(id),
      controleStore.index("by-rdo-id").getAllKeys(id),
    ]);
    const correlatedEventKeyGroups = await Promise.all(
      rdoMutations.map((mutation) =>
        eventStore.index("by-client-mutation-id").getAllKeys(
          mutation.clientMutationId,
        ),
      ),
    );
    const eventKeysToDelete = [
      ...new Set([
        ...eventKeys,
        ...correlatedEventKeyGroups.flat(),
      ]),
    ];

    await Promise.all([
      rdoStore.delete(id),
      ...rdoMutations.map((mutation) =>
        outboxStore.delete(mutation.clientMutationId),
      ),
      ...eventKeysToDelete.map((key) => eventStore.delete(key)),
      ...attachmentKeys.map((key) => attachmentStore.delete(key)),
      ...maoObraKeys.map((key) => maoObraStore.delete(key)),
      ...equipamentoKeys.map((key) => equipamentoStore.delete(key)),
      ...materialKeys.map((key) => materialStore.delete(key)),
      ...controleKeys.map((key) => controleStore.delete(key)),
    ]);

    await transaction.done;
  } catch (error) {
    try {
      transaction.abort();
    } catch {
      // A transação pode já ter sido abortada pelo IndexedDB.
    }
    await transaction.done.catch(() => undefined);
    throw error;
  }
}
