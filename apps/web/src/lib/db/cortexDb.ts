import {
  openDB,
  type DBSchema,
  type IDBPDatabase,
} from "idb";

import type {
  LocalRdoControleGeometricoRecord,
  LocalRdoEquipamentoRecord,
  LocalRdoMaoObraRecord,
  LocalRdoMaterialRecord,
  LocalRdoRecord,
  OutboxMutationRecord,
  ProcessedEventRecord,
  RdoAttachmentRecord,
  StaviaSnapshotRecord,
  SyncStateRecord,
} from "./db.types";

const DATABASE_NAME = "cortex-web";
const DATABASE_VERSION = 5;

interface CortexDbSchema extends DBSchema {
  rdos: {
    key: string;
    value: LocalRdoRecord;
    indexes: {
      "by-sync-status": LocalRdoRecord["syncStatus"];
      "by-updated-at": string;
      "by-obra-id": string;
    };
  };

  rdoMaoObra: {
    key: string;
    value: LocalRdoMaoObraRecord;
    indexes: {
      "by-rdo-id": string;
      "by-sync-status": LocalRdoMaoObraRecord["syncStatus"];
      "by-updated-at": string;
    };
  };

  rdoEquipamentos: {
    key: string;
    value: LocalRdoEquipamentoRecord;
    indexes: {
      "by-rdo-id": string;
      "by-sync-status": LocalRdoEquipamentoRecord["syncStatus"];
      "by-updated-at": string;
    };
  };

  rdoMateriais: {
    key: string;
    value: LocalRdoMaterialRecord;
    indexes: {
      "by-rdo-id": string;
      "by-sync-status": LocalRdoMaterialRecord["syncStatus"];
      "by-updated-at": string;
    };
  };

  rdoControlesGeometricos: {
    key: string;
    value: LocalRdoControleGeometricoRecord;
    indexes: {
      "by-rdo-id": string;
      "by-sync-status": LocalRdoControleGeometricoRecord["syncStatus"];
      "by-updated-at": string;
    };
  };

  outbox_mutations: {
    key: string;
    value: OutboxMutationRecord;
    indexes: {
      "by-status": OutboxMutationRecord["status"];
      "by-created-at": string;
      "by-entity-id": string;
    };
  };

  sync_state: {
    key: "default";
    value: SyncStateRecord;
  };

  processed_events: {
    key: number;
    value: ProcessedEventRecord;
    indexes: {
      "by-entity-id": string;
      "by-applied-at": string;
    };
  };

  rdo_attachments: {
    key: string;
    value: RdoAttachmentRecord;
    indexes: {
      "by-rdo-id": string;
      "by-sync-status": RdoAttachmentRecord["syncStatus"];
      "by-created-at": string;
    };
  };

  stavia_snapshots: {
    key: "default";
    value: StaviaSnapshotRecord;
    indexes: {
      "by-updated-at": string;
    };
  };
}

let databasePromise:
  | Promise<IDBPDatabase<CortexDbSchema>>
  | null = null;

export function getCortexDb(): Promise<
  IDBPDatabase<CortexDbSchema>
> {
  if (databasePromise) {
    return databasePromise;
  }

  databasePromise = openDB<CortexDbSchema>(
    DATABASE_NAME,
    DATABASE_VERSION,
    {
      upgrade(database) {
        if (!database.objectStoreNames.contains("rdos")) {
          const rdoStore = database.createObjectStore("rdos", {
            keyPath: "id",
          });

          rdoStore.createIndex("by-sync-status", "syncStatus");
          rdoStore.createIndex("by-updated-at", "updatedAt");
          rdoStore.createIndex("by-obra-id", "obraId");
        }

        if (
          !database.objectStoreNames.contains(
            "rdoMaoObra",
          )
        ) {
          const rdoMaoObraStore =
            database.createObjectStore(
              "rdoMaoObra",
              {
                keyPath: "id",
              },
            );

          rdoMaoObraStore.createIndex(
            "by-rdo-id",
            "rdoId",
          );
          rdoMaoObraStore.createIndex(
            "by-sync-status",
            "syncStatus",
          );
          rdoMaoObraStore.createIndex(
            "by-updated-at",
            "updatedAt",
          );
        }

        if (
          !database.objectStoreNames.contains(
            "rdoEquipamentos",
          )
        ) {
          const rdoEquipamentosStore =
            database.createObjectStore(
              "rdoEquipamentos",
              {
                keyPath: "id",
              },
            );

          rdoEquipamentosStore.createIndex(
            "by-rdo-id",
            "rdoId",
          );
          rdoEquipamentosStore.createIndex(
            "by-sync-status",
            "syncStatus",
          );
          rdoEquipamentosStore.createIndex(
            "by-updated-at",
            "updatedAt",
          );
        }

        if (
          !database.objectStoreNames.contains(
            "rdoMateriais",
          )
        ) {
          const rdoMateriaisStore =
            database.createObjectStore(
              "rdoMateriais",
              {
                keyPath: "id",
              },
            );

          rdoMateriaisStore.createIndex(
            "by-rdo-id",
            "rdoId",
          );
          rdoMateriaisStore.createIndex(
            "by-sync-status",
            "syncStatus",
          );
          rdoMateriaisStore.createIndex(
            "by-updated-at",
            "updatedAt",
          );
        }

        if (
          !database.objectStoreNames.contains(
            "rdoControlesGeometricos",
          )
        ) {
          const rdoControlesStore =
            database.createObjectStore(
              "rdoControlesGeometricos",
              {
                keyPath: "id",
              },
            );

          rdoControlesStore.createIndex(
            "by-rdo-id",
            "rdoId",
          );
          rdoControlesStore.createIndex(
            "by-sync-status",
            "syncStatus",
          );
          rdoControlesStore.createIndex(
            "by-updated-at",
            "updatedAt",
          );
        }

        if (
          !database.objectStoreNames.contains(
            "outbox_mutations",
          )
        ) {
          const outboxStore = database.createObjectStore(
            "outbox_mutations",
            {
              keyPath: "clientMutationId",
            },
          );

          outboxStore.createIndex("by-status", "status");
          outboxStore.createIndex(
            "by-created-at",
            "criadaNoClienteEm",
          );
          outboxStore.createIndex(
            "by-entity-id",
            "entidadeId",
          );
        }

        if (
          !database.objectStoreNames.contains("sync_state")
        ) {
          database.createObjectStore("sync_state", {
            keyPath: "key",
          });
        }

        if (
          !database.objectStoreNames.contains(
            "processed_events",
          )
        ) {
          const processedEventsStore =
            database.createObjectStore(
              "processed_events",
              {
                keyPath: "commitSeq",
              },
            );

          processedEventsStore.createIndex(
            "by-entity-id",
            "entidadeId",
          );
          processedEventsStore.createIndex(
            "by-applied-at",
            "aplicadoEm",
          );
        }

        if (
          !database.objectStoreNames.contains(
            "rdo_attachments",
          )
        ) {
          const attachmentStore =
            database.createObjectStore(
              "rdo_attachments",
              {
                keyPath: "id",
              },
            );

          attachmentStore.createIndex(
            "by-rdo-id",
            "rdoId",
          );
          attachmentStore.createIndex(
            "by-sync-status",
            "syncStatus",
          );
          attachmentStore.createIndex(
            "by-created-at",
            "createdAt",
          );
        }

        if (
          !database.objectStoreNames.contains(
            "stavia_snapshots",
          )
        ) {
          const staviaSnapshotStore =
            database.createObjectStore(
              "stavia_snapshots",
              {
                keyPath: "key",
              },
            );

          staviaSnapshotStore.createIndex(
            "by-updated-at",
            "updatedAt",
          );
        }
      },

      blocked() {
        console.warn(
          "A atualização do IndexedDB foi bloqueada por outra aba aberta.",
        );
      },

      blocking() {
        console.warn(
          "Esta aba está bloqueando uma nova versão do IndexedDB.",
        );
      },

      terminated() {
        databasePromise = null;

        console.error(
          "A conexão com o IndexedDB foi encerrada inesperadamente.",
        );
      },
    },
  );

  return databasePromise;
}

export async function initializeCortexDb(): Promise<void> {
  const database = await getCortexDb();

  const existingSyncState =
    await database.get("sync_state", "default");

  if (!existingSyncState) {
    const initialSyncState: SyncStateRecord = {
      key: "default",
      deviceId: null,
      lastPulledCommitSeq: 0,
      lastAckedCommitSeq: 0,
      isSyncing: false,
      lastSyncStartedAt: null,
      lastSyncCompletedAt: null,
      lastSyncError: null,
    };

    await database.put("sync_state", initialSyncState);
  }
}
