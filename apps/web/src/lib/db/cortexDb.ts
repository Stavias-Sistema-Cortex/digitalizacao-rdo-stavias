import {
  openDB,
  type DBSchema,
  type IDBPDatabase,
} from "idb";

import type {
  ColaboradorLocalRecord,
  ConversaLocalRecord,
  LocalRdoControleGeometricoRecord,
  LocalRdoEquipamentoRecord,
  LocalRdoMaoObraRecord,
  LocalRdoMaterialRecord,
  LocalRdoRecord,
  MensagemAnexoLocalRecord,
  MensagemLocalRecord,
  LocalOperationalRoleRecord,
  ObraLocalRecord,
  OperationalEventRecord,
  OutboxMutationRecord,
  PrevisaoSnapshotRecord,
  ProcessedEventRecord,
  RdoAttachmentRecord,
  SyncStateRecord,
  TarefaRecord,
  LocalTeamHistoryRecord,
  LocalTeamRecord,
  LocalTeamWorksiteRecord,
} from "./db.types";
import { AUTH_SESSION_CHANGED_EVENT } from "../../features/auth/authSession";
import { currentDataDatabaseName } from "./localDataNamespace";

export const CORTEX_DATABASE_VERSION = 13;
const LEGACY_ASSISTANT_STORE = "stavia_snapshots";

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

  mensagem_conversas: {
    key: string;
    value: ConversaLocalRecord;
    indexes: {
      "by-updated-at": string;
      "by-type": ConversaLocalRecord["tipo"];
      "by-obra-id": string;
    };
  };

  mensagens: {
    key: string;
    value: MensagemLocalRecord;
    indexes: {
      "by-conversation-id": string;
      "by-created-at": string;
      "by-sync-status": MensagemLocalRecord["syncStatus"];
      "by-client-mutation-id": string;
    };
  };

  mensagem_anexos: {
    key: string;
    value: MensagemAnexoLocalRecord;
    indexes: {
      "by-message-id": string;
      "by-conversation-id": string;
      "by-sync-status": MensagemAnexoLocalRecord["syncStatus"];
      "by-upload-mutation-id": string;
    };
  };

  teams: {
    key: string;
    value: LocalTeamRecord;
    indexes: {
      "by-updated-at": string;
    };
  };

  operational_roles: {
    key: string;
    value: LocalOperationalRoleRecord;
  };

  team_history: {
    key: [string, number];
    value: LocalTeamHistoryRecord;
    indexes: {
      "by-team-commit": [string, number];
    };
  };

  team_worksites: {
    key: string;
    value: LocalTeamWorksiteRecord;
    indexes: {
      "by-team-id": string;
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
    CORTEX_DATABASE_VERSION,
    {
      upgrade(database, oldVersion, _newVersion, transaction) {
        const legacyDatabase =
          database as unknown as Pick<
            IDBDatabase,
            "objectStoreNames" | "deleteObjectStore"
          >;
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
          oldVersion < 13 &&
          legacyDatabase.objectStoreNames.contains(LEGACY_ASSISTANT_STORE)
        ) {
          legacyDatabase.deleteObjectStore(LEGACY_ASSISTANT_STORE);
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

        if (
          !database.objectStoreNames.contains(
            "mensagem_conversas",
          )
        ) {
          const conversationStore = database.createObjectStore(
            "mensagem_conversas",
            { keyPath: "id" },
          );
          conversationStore.createIndex(
            "by-updated-at",
            "atualizadaEm",
          );
          conversationStore.createIndex("by-type", "tipo");
          conversationStore.createIndex("by-obra-id", "obraId");
        }

        if (!database.objectStoreNames.contains("mensagens")) {
          const messageStore = database.createObjectStore(
            "mensagens",
            { keyPath: "id" },
          );
          messageStore.createIndex(
            "by-conversation-id",
            "conversaId",
          );
          messageStore.createIndex(
            "by-created-at",
            "criadaNoClienteEm",
          );
          messageStore.createIndex(
            "by-sync-status",
            "syncStatus",
          );
          messageStore.createIndex(
            "by-client-mutation-id",
            "clientMutationId",
            { unique: true },
          );
        }

        if (
          !database.objectStoreNames.contains(
            "mensagem_anexos",
          )
        ) {
          const messageAttachmentStore = database.createObjectStore(
            "mensagem_anexos",
            { keyPath: "id" },
          );
          messageAttachmentStore.createIndex(
            "by-message-id",
            "mensagemId",
          );
          messageAttachmentStore.createIndex(
            "by-conversation-id",
            "conversaId",
          );
          messageAttachmentStore.createIndex(
            "by-sync-status",
            "syncStatus",
          );
          messageAttachmentStore.createIndex(
            "by-upload-mutation-id",
            "uploadMutationId",
            { unique: true },
          );
        }

        if (!database.objectStoreNames.contains("teams")) {
          const teamsStore = database.createObjectStore("teams", {
            keyPath: "id",
          });
          teamsStore.createIndex("by-updated-at", "atualizadoEm");
        }

        if (!database.objectStoreNames.contains("operational_roles")) {
          database.createObjectStore("operational_roles", {
            keyPath: "id",
          });
        }

        if (!database.objectStoreNames.contains("team_history")) {
          const historyStore = database.createObjectStore("team_history", {
            keyPath: ["entityId", "commitSeq"],
          });
          historyStore.createIndex(
            "by-team-commit",
            ["entityId", "commitSeq"],
          );
        }

        if (!database.objectStoreNames.contains("team_worksites")) {
          const worksitesStore = database.createObjectStore(
            "team_worksites",
            { keyPath: "id" },
          );
          worksitesStore.createIndex("by-team-id", "equipeId");
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

export async function closeCortexDb(): Promise<void> {
  const connections = await Promise.all(
    [...databasePromises.values()].map(async (promise) => await promise),
  );
  for (const database of connections) {
    database.close();
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
