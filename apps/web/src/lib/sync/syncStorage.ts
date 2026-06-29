import { getCortexDb } from "../db/cortexDb";
import type {
  LocalRdoChildRecord,
  LocalRdoRecord,
  LocalSyncStatus,
  OutboxMutationRecord,
  ProcessedEventRecord,
} from "../db/db.types";
import type {
  SyncPullEvent,
  SyncPushMutationResult,
} from "./sync.types";

function nowUtc(): string {
  return new Date().toISOString();
}

type RdoChildStoreName =
  | "rdoMaoObra"
  | "rdoEquipamentos"
  | "rdoMateriais"
  | "rdoControlesGeometricos";

interface RdoChildStoreUpdater {
  index: (name: "by-rdo-id") => {
    getAll: (
      query: string,
    ) => Promise<LocalRdoChildRecord[]>;
  };
  put: (
    value: LocalRdoChildRecord,
  ) => Promise<IDBValidKey>;
}

interface RdoChildSyncTransaction {
  objectStore: (
    name: RdoChildStoreName,
  ) => RdoChildStoreUpdater;
}

const RDO_CHILD_STORE_NAMES = [
  "rdoMaoObra",
  "rdoEquipamentos",
  "rdoMateriais",
  "rdoControlesGeometricos",
] as const;

const RDO_SYNC_TRANSACTION_STORES = [
  "outbox_mutations",
  "rdos",
  ...RDO_CHILD_STORE_NAMES,
] as const;

async function updateRdoChildrenSyncStatus(
  transaction: RdoChildSyncTransaction,
  rdoId: string,
  syncStatus: LocalSyncStatus,
  timestamp: string,
): Promise<void> {
  await Promise.all(
    RDO_CHILD_STORE_NAMES.map(async (storeName) => {
      const store = transaction.objectStore(storeName);
      const records = await store
        .index("by-rdo-id")
        .getAll(rdoId);

      await Promise.all(
        records.map((record: LocalRdoChildRecord) =>
          store.put({
            ...record,
            syncStatus,
            updatedAt: timestamp,
          }),
        ),
      );
    }),
  );
}

export async function recoverInterruptedMutations(): Promise<void> {
  const database = await getCortexDb();

  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );

  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");

  const syncingMutations =
    await outboxStore.index("by-status").getAll("SYNCING");

  for (const mutation of syncingMutations) {
    const updatedMutation: OutboxMutationRecord = {
      ...mutation,
      status: "PENDING",
      ultimoErro:
        "Sincronização anterior foi interrompida antes da confirmação.",
      updatedAt: nowUtc(),
    };

    await outboxStore.put(updatedMutation);

    const rdo = await rdoStore.get(mutation.entidadeId);

    if (rdo) {
      await rdoStore.put({
        ...rdo,
        syncStatus: "PENDING_SYNC",
        updatedAt: nowUtc(),
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        nowUtc(),
      );
    }
  }

  await transaction.done;
}

export async function markMutationAsSyncing(
  mutation: OutboxMutationRecord,
): Promise<void> {
  const database = await getCortexDb();

  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );

  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");

  const currentMutation = await outboxStore.get(
    mutation.clientMutationId,
  );

  if (!currentMutation) {
    transaction.abort();

    throw new Error(
      `Mutação ${mutation.clientMutationId} não encontrada.`,
    );
  }

  if (currentMutation.status !== "PENDING") {
    transaction.abort();

    throw new Error(
      `Mutação ${mutation.clientMutationId} não está pendente.`,
    );
  }

  await outboxStore.put({
    ...currentMutation,
    status: "SYNCING",
    tentativas: currentMutation.tentativas + 1,
    ultimaTentativaEm: nowUtc(),
    ultimoErro: null,
    updatedAt: nowUtc(),
  });

  const rdo = await rdoStore.get(currentMutation.entidadeId);

  if (rdo) {
    const timestamp = nowUtc();

    await rdoStore.put({
      ...rdo,
      syncStatus: "SYNCING",
      updatedAt: timestamp,
    });

    await updateRdoChildrenSyncStatus(
      transaction,
      currentMutation.entidadeId,
      "SYNCING",
      timestamp,
    );
  }

  await transaction.done;
}

export async function applyPushResultAtomically(
  result: SyncPushMutationResult,
): Promise<void> {
  const database = await getCortexDb();

  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );

  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");

  const mutation = await outboxStore.get(
    result.clientMutationId,
  );

  if (!mutation) {
    transaction.abort();

    throw new Error(
      `Resultado recebido para mutação desconhecida: ${result.clientMutationId}.`,
    );
  }

  const rdo = await rdoStore.get(mutation.entidadeId);
  const timestamp = nowUtc();

  if (result.status === "APLICADA") {
    await outboxStore.put({
      ...mutation,
      status: "SYNCED",
      ultimoErro: null,
      conflito: null,
      updatedAt: timestamp,
    });

    if (rdo) {
      const resultVersion =
        result.resultado &&
        typeof result.resultado.versaoEntidade === "number"
          ? result.resultado.versaoEntidade
          : rdo.versaoEntidade;

      await rdoStore.put({
        ...rdo,
        syncStatus: "SYNCED",
        versaoEntidade: resultVersion,
        updatedAt: timestamp,
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        "SYNCED",
        timestamp,
      );
    }
  } else if (result.status === "DESCARTADA") {
    await outboxStore.put({
      ...mutation,
      status: "CONFLICT",
      ultimoErro:
        result.erro ?? "Conflito informado pelo servidor.",
      conflito: result.conflito ?? null,
      updatedAt: timestamp,
    });

    if (rdo) {
      await rdoStore.put({
        ...rdo,
        syncStatus: "CONFLICT",
        updatedAt: timestamp,
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        "CONFLICT",
        timestamp,
      );
    }
  } else {
    await outboxStore.put({
      ...mutation,
      status: "ERROR",
      ultimoErro:
        result.erro ?? "Erro informado pelo servidor.",
      conflito: result.conflito ?? null,
      updatedAt: timestamp,
    });

    if (rdo) {
      await rdoStore.put({
        ...rdo,
        syncStatus: "ERROR",
        updatedAt: timestamp,
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        "ERROR",
        timestamp,
      );
    }
  }

  await transaction.done;
}

export async function returnMutationToPending(
  clientMutationId: string,
  errorMessage: string,
): Promise<void> {
  const database = await getCortexDb();

  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );

  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");

  const mutation = await outboxStore.get(clientMutationId);

  if (!mutation) {
    await transaction.done;
    return;
  }

  const timestamp = nowUtc();

  await outboxStore.put({
    ...mutation,
    status: "PENDING",
    ultimoErro: errorMessage,
    updatedAt: timestamp,
  });

  const rdo = await rdoStore.get(mutation.entidadeId);

  if (rdo) {
    await rdoStore.put({
      ...rdo,
      syncStatus: "PENDING_SYNC",
      updatedAt: timestamp,
    });

    await updateRdoChildrenSyncStatus(
      transaction,
      mutation.entidadeId,
      "PENDING_SYNC",
      timestamp,
    );
  }

  await transaction.done;
}

function applySafeRdoEvent(
  rdo: LocalRdoRecord,
  event: SyncPullEvent,
): LocalRdoRecord {
  const updated: LocalRdoRecord = {
    ...rdo,
    updatedAt: nowUtc(),
  };

  if (
    typeof event.versaoEntidade === "number" &&
    event.versaoEntidade >=
      (rdo.versaoEntidade ?? 0)
  ) {
    updated.versaoEntidade = event.versaoEntidade;
  }

  if (event.tipoEvento === "RDO_ENVIADO") {
    updated.statusRdo = "ENVIADO";
  }

  return updated;
}

export async function applyPulledEventsAtomically(
  events: SyncPullEvent[],
  nextCommitSeq: number,
): Promise<number> {
  const database = await getCortexDb();

  const transaction = database.transaction(
    ["processed_events", "rdos", "sync_state"],
    "readwrite",
  );

  const processedStore =
    transaction.objectStore("processed_events");
  const rdoStore = transaction.objectStore("rdos");
  const syncStateStore =
    transaction.objectStore("sync_state");

  const syncState = await syncStateStore.get("default");

  if (!syncState) {
    transaction.abort();

    throw new Error(
      "Estado local de sincronização não encontrado.",
    );
  }

  let highestAppliedCommitSeq =
    syncState.lastPulledCommitSeq;

  const orderedEvents = [...events].sort(
    (left, right) => left.commitSeq - right.commitSeq,
  );

  for (const event of orderedEvents) {
    if (!Number.isSafeInteger(event.commitSeq)) {
      transaction.abort();

      throw new Error(
        "Evento recebido com commitSeq inválido.",
      );
    }

    if (event.commitSeq <= syncState.lastPulledCommitSeq) {
      continue;
    }

    const alreadyProcessed = await processedStore.get(
      event.commitSeq,
    );

    if (alreadyProcessed) {
      highestAppliedCommitSeq = Math.max(
        highestAppliedCommitSeq,
        event.commitSeq,
      );

      continue;
    }

    if (
      event.entidadeTipo === "RDO" &&
      event.entidadeId
    ) {
      const localRdo = await rdoStore.get(
        event.entidadeId,
      );

      if (localRdo) {
        await rdoStore.put(
          applySafeRdoEvent(localRdo, event),
        );
      }
    }

    const processedRecord: ProcessedEventRecord = {
      commitSeq: event.commitSeq,
      eventoId: event.eventoId,
      tipoEvento: event.tipoEvento,
      entidadeTipo: event.entidadeTipo,
      entidadeId: event.entidadeId,
      aplicadoEm: nowUtc(),
    };

    await processedStore.add(processedRecord);

    highestAppliedCommitSeq = Math.max(
      highestAppliedCommitSeq,
      event.commitSeq,
    );
  }

  if (
    events.length === 0 &&
    nextCommitSeq > syncState.lastPulledCommitSeq
  ) {
    transaction.abort();

    throw new Error(
      "O servidor avançou o cursor sem entregar eventos.",
    );
  }

  const resultingCursor = Math.max(
    highestAppliedCommitSeq,
    events.length > 0
      ? Math.min(nextCommitSeq, highestAppliedCommitSeq)
      : syncState.lastPulledCommitSeq,
  );

  await syncStateStore.put({
    ...syncState,
    lastPulledCommitSeq: resultingCursor,
  });

  await transaction.done;

  return resultingCursor;
}
