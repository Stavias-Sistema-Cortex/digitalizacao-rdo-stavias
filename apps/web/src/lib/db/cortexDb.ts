import {
  openDB,
  type DBSchema,
  type IDBPDatabase,
} from "idb";

import type {
  ColaboradorLocalRecord,
  LocalConversationParticipantRecord,
  LocalConversationRecord,
  LocalMessageAttachmentRecord,
  LocalMessageRecord,
  LocalMessageReferenceRecord,
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

export const CORTEX_DATABASE_NAME = "cortex-web";
export const CORTEX_DATABASE_VERSION = 10;

interface CortexDbSchema extends DBSchema {
  conversations: {
    key: string;
    value: LocalConversationRecord;
    indexes: {
      "by-activity": string;
      "by-obra-id": string;
      "by-equipe-id": string;
      "by-status": LocalConversationRecord["status"];
    };
  };

  conversation_participants: {
    key: string;
    value: LocalConversationParticipantRecord;
    indexes: {
      "by-conversation-id": string;
      "by-collaborator-id": string;
      "by-status": LocalConversationParticipantRecord["status"];
    };
  };

  messages: {
    key: string;
    value: LocalMessageRecord;
    indexes: {
      "by-conversation-order": [string, string, string];
      "by-client-message-id": string;
      "by-sync-status": LocalMessageRecord["syncStatus"];
    };
  };

  message_references: {
    key: string;
    value: LocalMessageReferenceRecord;
    indexes: {
      "by-message-id": string;
      "by-object": [string, string];
    };
  };

  message_attachments: {
    key: string;
    value: LocalMessageAttachmentRecord;
    indexes: {
      "by-message-id": string;
      "by-client-attachment-id": string;
      "by-sync-status": LocalMessageAttachmentRecord["syncStatus"];
    };
  };

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
    CORTEX_DATABASE_NAME,
    CORTEX_DATABASE_VERSION,
    {
      upgrade(database, _oldVersion, _newVersion, transaction) {
        if (!database.objectStoreNames.contains("conversations")) {
          const conversationStore = database.createObjectStore(
            "conversations",
            { keyPath: "id" },
          );
          conversationStore.createIndex(
            "by-activity",
            "ultimaAtividadeEm",
          );
          conversationStore.createIndex("by-obra-id", "obraId");
          conversationStore.createIndex("by-equipe-id", "equipeId");
          conversationStore.createIndex("by-status", "status");
        }

        if (
          !database.objectStoreNames.contains(
            "conversation_participants",
          )
        ) {
          const participantStore = database.createObjectStore(
            "conversation_participants",
            { keyPath: "id" },
          );
          participantStore.createIndex(
            "by-conversation-id",
            "conversaId",
          );
          participantStore.createIndex(
            "by-collaborator-id",
            "colaboradorId",
          );
          participantStore.createIndex("by-status", "status");
        }

        if (!database.objectStoreNames.contains("messages")) {
          const messageStore = database.createObjectStore("messages", {
            keyPath: "id",
          });
          messageStore.createIndex(
            "by-conversation-order",
            ["conversaId", "enviadaClienteEm", "id"],
          );
          messageStore.createIndex(
            "by-client-message-id",
            "clientMessageId",
            { unique: true },
          );
          messageStore.createIndex("by-sync-status", "syncStatus");
        }

        if (
          !database.objectStoreNames.contains("message_references")
        ) {
          const referenceStore = database.createObjectStore(
            "message_references",
            { keyPath: "id" },
          );
          referenceStore.createIndex("by-message-id", "mensagemId");
          referenceStore.createIndex(
            "by-object",
            ["tipoObjeto", "objetoId"],
          );
        }

        if (
          !database.objectStoreNames.contains("message_attachments")
        ) {
          const attachmentStore = database.createObjectStore(
            "message_attachments",
            { keyPath: "id" },
          );
          attachmentStore.createIndex("by-message-id", "mensagemId");
          attachmentStore.createIndex(
            "by-client-attachment-id",
            "clientAttachmentId",
            { unique: true },
          );
          attachmentStore.createIndex("by-sync-status", "syncStatus");
        }

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

export async function closeCortexDb(): Promise<void> {
  if (!databasePromise) {
    return;
  }

  const database = await databasePromise;
  database.close();
  databasePromise = null;
}
