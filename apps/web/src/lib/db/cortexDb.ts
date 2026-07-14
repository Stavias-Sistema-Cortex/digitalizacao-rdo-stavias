import {
  openDB,
  type DBSchema,
  type IDBPDatabase,
} from "idb";

import type {
  ColaboradorLocalRecord,
  LocalRdoControleGeometricoRecord,
  LocalRdoEquipamentoRecord,
  LocalRdoMaoObraRecord,
  LocalRdoMaterialRecord,
  LocalRdoRecord,
  ObraLocalRecord,
  OperationalEventRecord,
  OutboxMutationRecord,
  PrevisaoSnapshotRecord,
  ProcessedEventRecord,
  RdoAttachmentRecord,
  StaviaSnapshotRecord,
  SyncStateRecord,
  TarefaRecord,
} from "./db.types";
import { AUTH_SESSION_CHANGED_EVENT } from "../../features/auth/authSession";
import { currentDataDatabaseName } from "./localDataNamespace";

const DATABASE_VERSION = 10;

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

  operational_events: {
    key: string;
    value: OperationalEventRecord;
    indexes: {
      "by-principal-entity": string;
      "by-rdo-id": string;
      "by-obra-id": string;
      "by-colaborador-id": string;
      "by-type": OperationalEventRecord["type"];
      "by-sync-status": OperationalEventRecord["syncStatus"];
      "by-occurred-at": string;
    };
  };

  rdo_attachments: {
    key: string;
    value: RdoAttachmentRecord;
    indexes: {
      "by-rdo-id": string;
      "by-obra-id": string;
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

  obras: {
    key: string;
    value: ObraLocalRecord;
    indexes: {
      "by-updated-at": string;
      "by-status": string;
    };
  };

  previsao_snapshots: {
    key: string;
    value: PrevisaoSnapshotRecord;
    indexes: {
      "by-obra-id": string;
      "by-data-referencia": string;
    };
  };

  tarefas: {
    key: string;
    value: TarefaRecord;
    indexes: {
      "by-obra-id": string;
      "by-updated-at": string;
    };
  };

  colaboradores: {
    key: string;
    value: ColaboradorLocalRecord;
    indexes: {
      "by-nome": string;
    };
  };
}

const databasePromises = new Map<
  string,
  Promise<IDBPDatabase<CortexDbSchema>>
>();

export async function getCortexDb(): Promise<
  IDBPDatabase<CortexDbSchema>
> {
  const databaseName = await currentDataDatabaseName();
  const existing = databasePromises.get(databaseName);
  if (existing) {
    return existing;
  }

  const promise = openDB<CortexDbSchema>(
    databaseName,
    DATABASE_VERSION,
    {
      upgrade(database, _oldVersion, _newVersion, transaction) {
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
            "operational_events",
          )
        ) {
          const eventStore =
            database.createObjectStore(
              "operational_events",
              {
                keyPath: "id",
              },
            );

          eventStore.createIndex(
            "by-principal-entity",
            "principalEntityKey",
          );
          eventStore.createIndex(
            "by-rdo-id",
            "rdoId",
          );
          eventStore.createIndex(
            "by-obra-id",
            "obraId",
          );
          eventStore.createIndex(
            "by-colaborador-id",
            "colaboradorId",
          );
          eventStore.createIndex("by-type", "type");
          eventStore.createIndex(
            "by-sync-status",
            "syncStatus",
          );
          eventStore.createIndex(
            "by-occurred-at",
            "occurredAt",
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
            "by-obra-id",
            "obraId",
          );
          attachmentStore.createIndex(
            "by-sync-status",
            "syncStatus",
          );
          attachmentStore.createIndex(
            "by-created-at",
            "createdAt",
          );
        } else {
          const attachmentStore =
            transaction.objectStore("rdo_attachments");

          if (
            !attachmentStore.indexNames.contains(
              "by-obra-id",
            )
          ) {
            attachmentStore.createIndex(
              "by-obra-id",
              "obraId",
            );
          }
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

        if (!database.objectStoreNames.contains("obras")) {
          const obraStore = database.createObjectStore("obras", {
            keyPath: "id",
          });

          obraStore.createIndex("by-updated-at", "updatedAt");
          obraStore.createIndex("by-status", "status");
        }

        if (
          !database.objectStoreNames.contains("previsao_snapshots")
        ) {
          const snapshotStore = database.createObjectStore(
            "previsao_snapshots",
            {
              keyPath: "id",
            },
          );

          snapshotStore.createIndex("by-obra-id", "obraId");
          snapshotStore.createIndex(
            "by-data-referencia",
            "dataReferencia",
          );
        }

        if (
          !database.objectStoreNames.contains("tarefas")
        ) {
          const tarefaStore = database.createObjectStore(
            "tarefas",
            {
              keyPath: "id",
            },
          );

          tarefaStore.createIndex("by-obra-id", "obraId");
          tarefaStore.createIndex(
            "by-updated-at",
            "updatedAt",
          );
        }

        if (
          !database.objectStoreNames.contains(
            "colaboradores",
          )
        ) {
          const colaboradorStore =
            database.createObjectStore("colaboradores", {
              keyPath: "id",
            });

          colaboradorStore.createIndex("by-nome", "nome");
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
        databasePromises.delete(databaseName);

        console.error(
          "A conexão com o IndexedDB foi encerrada inesperadamente.",
        );
      },
    },
  );
  databasePromises.set(databaseName, promise);
  try {
    return await promise;
  } catch (error: unknown) {
    databasePromises.delete(databaseName);
    throw error;
  }
}

export async function initializeCortexDb(): Promise<void> {
  const database = await getCortexDb();

  const existingSyncState =
    await database.get("sync_state", "default");

  if (!existingSyncState) {
    const initialSyncState: SyncStateRecord = {
      key: "default",
      deviceId: null,
      usuarioId: null,
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

function closeDataConnections(): void {
  for (const promise of databasePromises.values()) {
    void promise.then((database) => database.close()).catch(() => undefined);
  }
  databasePromises.clear();
}

if (
  typeof window !== "undefined" &&
  typeof window.addEventListener === "function"
) {
  window.addEventListener(
    AUTH_SESSION_CHANGED_EVENT,
    closeDataConnections,
  );
}
