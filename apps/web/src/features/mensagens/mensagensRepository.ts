import { getCortexDb } from "../../lib/db/cortexDb";
import type {
  CanonicalOutboxMutationRecord,
  ConversaLocalRecord,
  MensagemAnexoLocalRecord,
  MensagemLocalRecord,
} from "../../lib/db/db.types";
import {
  getSyncState,
  updateSyncState,
} from "../../lib/db/syncStateRepository";
import { commitLocalMutation } from "../../lib/sync/localMutationCoordinator";
import type { MutationActor } from "../../lib/sync/mutationEnvelope";
import { getSession } from "../auth/authSession";
import type {
  ConversationApi,
  MessageApi,
} from "./mensagensApi";
import {
  buildQueuedMessage,
  type QueuedMessagePlan,
} from "./mensagensQueue";
import {
  buildConversationPreviews,
  type ConversationPreview,
} from "./mensagensView";

export const MESSAGES_CHANGED_EVENT =
  "cortex-messages-changed";

const DEFAULT_MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024;
const DEFAULT_ALLOWED_MEDIA_TYPES = new Set([
  "application/pdf",
  "application/zip",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "application/vnd.openxmlformats-officedocument.presentationml.presentation",
  "image/png",
  "image/jpeg",
  "image/gif",
  "image/webp",
  "text/plain",
  "text/csv",
]);

export interface MensagemComAnexos extends MensagemLocalRecord {
  anexos: MensagemAnexoLocalRecord[];
}

export function maxMessageAttachmentBytes(): number {
  const configured = Number(
    import.meta.env.VITE_CORTEX_MESSAGE_MAX_ATTACHMENT_BYTES,
  );
  return Number.isSafeInteger(configured) && configured > 0
    ? configured
    : DEFAULT_MAX_ATTACHMENT_BYTES;
}

export function validateMessageFiles(files: File[]): void {
  const maxBytes = maxMessageAttachmentBytes();
  for (const file of files) {
    if (file.size <= 0) {
      throw new Error(`O anexo ${file.name || "sem nome"} está vazio.`);
    }
    if (file.size > maxBytes) {
      throw new Error(
        `O anexo ${file.name || "sem nome"} excede o limite de ${formatBytes(maxBytes)}.`,
      );
    }
    const type = file.type || "application/octet-stream";
    if (!DEFAULT_ALLOWED_MEDIA_TYPES.has(type)) {
      throw new Error(`O tipo ${type} não é permitido para anexos.`);
    }
  }
}

function canonicalMessagePayload(
  message: MensagemLocalRecord,
  attachments: readonly MensagemAnexoLocalRecord[],
): Record<string, unknown> {
  const anexos = attachments
    .slice()
    .sort((left, right) => left.ordem - right.ordem)
    .map((attachment) => {
      if (!attachment.objetoId || !attachment.sha256) {
        throw new Error(
          "O anexo ainda não possui uma referência íntegra do servidor.",
        );
      }
      return {
        objetoId: attachment.objetoId,
        sha256: attachment.sha256,
      };
    });

  return {
    conversaId: message.conversaId,
    corpo: message.corpo,
    anexos,
  };
}

async function messageMutationActor(
  message: MensagemLocalRecord,
  conversation: ConversaLocalRecord | undefined,
): Promise<MutationActor> {
  const session = getSession();
  if (!session) {
    throw new Error(
      "Abra uma sessão protegida antes de preparar a mensagem para sincronização.",
    );
  }
  if (session.colaboradorId !== message.autorId) {
    throw new Error(
      "A mensagem local pertence a outra sessão e requer revisão antes de sincronizar.",
    );
  }

  const authorizationScope = conversation?.obraId
    ? [conversation.obraId]
    : session.obraIds;
  if (authorizationScope.length === 0 && !session.escopoGlobal) {
    throw new Error(
      "A conversa sem obra não possui um escopo canônico para sincronização.",
    );
  }

  const syncState = await getSyncState();
  const deviceId =
    syncState.usuarioId === session.colaboradorId && syncState.deviceId
      ? syncState.deviceId
      : crypto.randomUUID();
  if (
    syncState.usuarioId !== session.colaboradorId ||
    syncState.deviceId !== deviceId
  ) {
    await updateSyncState({
      usuarioId: session.colaboradorId,
      deviceId,
    });
  }

  return {
    actorId: session.colaboradorId,
    actorName: session.nome,
    deviceId,
    authorizationScope,
  };
}

async function commitCanonicalMessageMutation(input: {
  message: MensagemLocalRecord;
  attachments: readonly MensagemAnexoLocalRecord[];
  conversation: ConversaLocalRecord | undefined;
  writeMode: "add" | "put";
}): Promise<{
  message: MensagemLocalRecord;
  mutation: CanonicalOutboxMutationRecord;
}> {
  const actor = await messageMutationActor(
    input.message,
    input.conversation,
  );
  const payload = canonicalMessagePayload(
    input.message,
    input.attachments,
  );
  const { mutation } = await commitLocalMutation({
    stores: ["mensagens"],
    entity: {
      type: "MENSAGEM",
      id: input.message.id,
      obraId: input.conversation?.obraId ?? null,
      colaboradorId: actor.actorId,
      relatedEntities: [
        { tipo: "CONVERSA", id: input.message.conversaId },
      ],
    },
    eventType: "MENSAGEM_CRIADA",
    operation: "CRIAR_MENSAGEM",
    baseVersion: null,
    previousState: {},
    newState: payload,
    actor,
    createdAt: input.message.criadaNoClienteEm,
    write: (transaction, mutationForMessage) => {
      const record: MensagemLocalRecord = {
        ...input.message,
        clientMutationId: mutationForMessage.clientMutationId,
        syncStatus: "NA_FILA",
        ultimoErro: null,
        updatedAt: input.message.criadaNoClienteEm,
      };
      if (input.writeMode === "add") {
        void transaction.objectStore("mensagens").add(record).catch(
          () => undefined,
        );
      } else {
        void transaction.objectStore("mensagens").put(record).catch(
          () => undefined,
        );
      }
      return undefined;
    },
  });

  return {
    message: {
      ...input.message,
      clientMutationId: mutation.clientMutationId,
      syncStatus: "NA_FILA",
      ultimoErro: null,
      updatedAt: input.message.criadaNoClienteEm,
    },
    mutation,
  };
}

/**
 * Materializes a message mutation only after every attachment has an immutable
 * server object id and hash. It never alters an existing canonical envelope.
 */
export async function materializeCanonicalMessageMutation(
  messageId: string,
): Promise<CanonicalOutboxMutationRecord | null> {
  const database = await getCortexDb();
  const [message, attachments, mutations] = await Promise.all([
    database.get("mensagens", messageId),
    database.getAllFromIndex(
      "mensagem_anexos",
      "by-message-id",
      messageId,
    ),
    database.getAll("outbox_mutations"),
  ]);
  if (!message || message.versaoEntidade !== null) {
    return null;
  }
  if (
    mutations.some(
      (mutation) =>
        mutation.entidadeTipo === "MENSAGEM" &&
        mutation.entidadeId === message.id &&
        mutation.operacao === "CRIAR_MENSAGEM",
    )
  ) {
    return null;
  }
  if (
    attachments.some(
      (attachment) =>
        attachment.syncStatus !== "SINCRONIZADO" ||
        !attachment.objetoId ||
        !attachment.sha256,
    )
  ) {
    return null;
  }

  const conversation = await database.get(
    "mensagem_conversas",
    message.conversaId,
  );
  const committed = await commitCanonicalMessageMutation({
    message,
    attachments,
    conversation,
    writeMode: "put",
  });
  return committed.mutation;
}

export async function materializeReadyMessageMutations(): Promise<number> {
  const database = await getCortexDb();
  const messages = await database.getAll("mensagens");
  let materialized = 0;

  for (const message of messages) {
    const mutation = await materializeCanonicalMessageMutation(message.id);
    if (mutation) {
      materialized += 1;
    }
  }

  return materialized;
}

export async function queueMessage(input: {
  conversaId: string;
  corpo: string;
  files: File[];
}): Promise<MensagemComAnexos> {
  const session = getSession();
  if (!session) {
    throw new Error("Abra a sessão protegida antes de criar mensagens locais.");
  }
  validateMessageFiles(input.files);
  const plan = buildQueuedMessage({
    ...input,
    autorId: session.colaboradorId,
    autorNome: session.nome,
  });
  const hashedPlan = await addAttachmentHashes(plan);
  const database = await getCortexDb();

  if (hashedPlan.attachments.length === 0) {
    const conversation = await database.get(
      "mensagem_conversas",
      hashedPlan.message.conversaId,
    );
    const committed = await commitCanonicalMessageMutation({
      message: hashedPlan.message,
      attachments: [],
      conversation,
      writeMode: "add",
    });
    emitMessagesChanged();
    return {
      ...committed.message,
      anexos: [],
    };
  }

  const transaction = database.transaction(
    ["mensagens", "mensagem_anexos", "outbox_mutations"],
    "readwrite",
  );

  await transaction.objectStore("mensagens").add(hashedPlan.message);
  for (const attachment of hashedPlan.attachments) {
    await transaction.objectStore("mensagem_anexos").add(attachment);
  }
  for (const mutation of hashedPlan.uploadMutations) {
    await transaction.objectStore("outbox_mutations").add(mutation);
  }
  await transaction.done;
  emitMessagesChanged();
  return {
    ...hashedPlan.message,
    anexos: hashedPlan.attachments,
  };
}

export async function listLocalConversations(): Promise<
  ConversaLocalRecord[]
> {
  const database = await getCortexDb();
  return (await database.getAll("mensagem_conversas")).sort(
    (left, right) => right.atualizadaEm.localeCompare(left.atualizadaEm),
  );
}

export async function listLocalConversationPreviews(): Promise<
  Record<string, ConversationPreview>
> {
  const database = await getCortexDb();
  const [messages, attachments] = await Promise.all([
    database.getAll("mensagens"),
    database.getAll("mensagem_anexos"),
  ]);
  return buildConversationPreviews(
    messages,
    new Set(attachments.map((attachment) => attachment.mensagemId)),
  );
}

export async function listLocalMessages(
  conversationId: string,
): Promise<MensagemComAnexos[]> {
  const database = await getCortexDb();
  const messages = await database.getAllFromIndex(
    "mensagens",
    "by-conversation-id",
    conversationId,
  );
  const attachments = await database.getAllFromIndex(
    "mensagem_anexos",
    "by-conversation-id",
    conversationId,
  );
  const byMessage = new Map<string, MensagemAnexoLocalRecord[]>();
  for (const attachment of attachments) {
    const current = byMessage.get(attachment.mensagemId) ?? [];
    current.push(attachment);
    byMessage.set(attachment.mensagemId, current);
  }
  return messages
    .sort((left, right) =>
      left.criadaNoClienteEm.localeCompare(right.criadaNoClienteEm),
    )
    .map((message) => ({
      ...message,
      anexos: (byMessage.get(message.id) ?? []).sort(
        (left, right) => left.ordem - right.ordem,
      ),
    }));
}

export async function searchLocalMessages(
  query: string,
): Promise<MensagemComAnexos[]> {
  const normalized = query.trim().toLocaleLowerCase("pt-BR");
  if (!normalized) {
    return [];
  }
  const database = await getCortexDb();
  const messages = (await database.getAll("mensagens")).filter((message) =>
    message.corpo?.toLocaleLowerCase("pt-BR").includes(normalized),
  );
  const allAttachments = await database.getAll("mensagem_anexos");
  return messages
    .sort((left, right) =>
      right.criadaNoClienteEm.localeCompare(left.criadaNoClienteEm),
    )
    .map((message) => ({
      ...message,
      anexos: allAttachments
        .filter((attachment) => attachment.mensagemId === message.id)
        .sort((left, right) => left.ordem - right.ordem),
    }));
}

export async function storeServerConversations(
  conversations: ConversationApi[],
  options: { authoritative?: boolean } = {},
): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    [
      "mensagem_conversas",
      "mensagens",
      "mensagem_anexos",
      "outbox_mutations",
    ],
    "readwrite",
  );
  const conversationStore = transaction.objectStore(
    "mensagem_conversas",
  );
  for (const conversation of conversations) {
    await conversationStore.put(conversationRecord(conversation));
  }
  if (options.authoritative) {
    const authorizedIds = new Set(
      conversations.map((conversation) => conversation.id),
    );
    for (const local of await conversationStore.getAll()) {
      if (authorizedIds.has(local.id)) {
        continue;
      }
      const messages = await transaction
        .objectStore("mensagens")
        .index("by-conversation-id")
        .getAll(local.id);
      const attachments = await transaction
        .objectStore("mensagem_anexos")
        .index("by-conversation-id")
        .getAll(local.id);
      const messageIds = new Set(messages.map((message) => message.id));
      const attachmentIds = new Set(
        attachments.map((attachment) => attachment.id),
      );
      const uploadMutationIds = new Set(
        attachments.flatMap((attachment) =>
          attachment.uploadMutationId
            ? [attachment.uploadMutationId]
            : [],
        ),
      );
      const outboxStore = transaction.objectStore("outbox_mutations");
      for (const mutation of await outboxStore.getAll()) {
        if (
          messageIds.has(mutation.entidadeId) ||
          attachmentIds.has(mutation.entidadeId) ||
          uploadMutationIds.has(mutation.clientMutationId) ||
          mutation.payload.conversaId === local.id
        ) {
          await outboxStore.delete(mutation.clientMutationId);
        }
      }
      for (const message of messages) {
        await transaction.objectStore("mensagens").delete(message.id);
      }
      for (const attachment of attachments) {
        await transaction
          .objectStore("mensagem_anexos")
          .delete(attachment.id);
      }
      await conversationStore.delete(local.id);
    }
  }
  await transaction.done;
  emitMessagesChanged();
}

export async function storeServerMessages(
  messages: MessageApi[],
): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["mensagens", "mensagem_anexos"],
    "readwrite",
  );
  const messageStore = transaction.objectStore("mensagens");
  const attachmentStore = transaction.objectStore("mensagem_anexos");
  const timestamp = new Date().toISOString();

  for (const message of messages) {
    const existing = await messageStore.get(message.id);
    const byMutation = existing
      ? undefined
      : await messageStore
          .index("by-client-mutation-id")
          .get(message.clientMutationId);
    const local = existing ?? byMutation;
    if (local && local.id !== message.id) {
      await messageStore.delete(local.id);
    }
    await messageStore.put({
      id: message.id,
      conversaId: message.conversaId,
      autorId: message.autorId,
      autorNome: message.autorNome,
      corpo: message.corpo,
      status: message.status,
      clientMutationId: message.clientMutationId,
      criadaNoClienteEm: message.criadaNoClienteEm,
      criadaEm: message.criadaEm,
      editadaEm: message.editadaEm,
      deletadaEm: message.deletadaEm,
      versaoEntidade: message.versao,
      syncStatus: "SINCRONIZADO",
      ultimoErro: null,
      updatedAt: timestamp,
    });

    const localAttachments = await attachmentStore
      .index("by-message-id")
      .getAll(local?.id ?? message.id);
    for (const serverAttachment of message.anexos) {
      const matchingLocal = localAttachments.find(
        (attachment) =>
          attachment.objetoId === serverAttachment.objetoId ||
          attachment.ordem === serverAttachment.ordem,
      );
      if (matchingLocal && matchingLocal.id !== serverAttachment.id) {
        await attachmentStore.delete(matchingLocal.id);
      }
      await attachmentStore.put({
        id: serverAttachment.id,
        mensagemId: message.id,
        conversaId: message.conversaId,
        objetoId: serverAttachment.objetoId,
        uploadMutationId: matchingLocal?.uploadMutationId ?? null,
        nome: serverAttachment.nome,
        mediaType: serverAttachment.mediaType,
        tamanhoBytes: serverAttachment.tamanhoBytes,
        sha256: serverAttachment.sha256,
        ordem: serverAttachment.ordem,
        arquivo: matchingLocal?.arquivo ?? null,
        syncStatus: "SINCRONIZADO",
        ultimoErro: null,
        createdAt: matchingLocal?.createdAt ?? timestamp,
        updatedAt: timestamp,
      });
    }
  }
  await transaction.done;
  emitMessagesChanged();
}

export async function retryMessage(messageId: string): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["mensagens", "mensagem_anexos", "outbox_mutations"],
    "readwrite",
  );
  const messageStore = transaction.objectStore("mensagens");
  const attachmentStore = transaction.objectStore("mensagem_anexos");
  const outboxStore = transaction.objectStore("outbox_mutations");
  const message = await messageStore.get(messageId);
  if (!message) {
    transaction.abort();
    throw new Error("Mensagem local não encontrada.");
  }
  const timestamp = new Date().toISOString();
  await messageStore.put({
    ...message,
    syncStatus: "NA_FILA",
    ultimoErro: null,
    updatedAt: timestamp,
  });
  const attachments = await attachmentStore
    .index("by-message-id")
    .getAll(messageId);
  const mutationIds = new Set([
    message.clientMutationId,
    ...attachments.flatMap((attachment) =>
      attachment.uploadMutationId ? [attachment.uploadMutationId] : [],
    ),
  ]);
  for (const mutationId of mutationIds) {
    const mutation = await outboxStore.get(mutationId);
    if (mutation && mutation.status !== "SYNCED") {
      await outboxStore.put({
        ...mutation,
        status: "PENDING",
        ultimoErro: null,
        conflito: null,
        updatedAt: timestamp,
      });
    }
  }
  for (const attachment of attachments) {
    if (attachment.syncStatus !== "SINCRONIZADO") {
      await attachmentStore.put({
        ...attachment,
        syncStatus: "NA_FILA",
        ultimoErro: null,
        updatedAt: timestamp,
      });
    }
  }
  await transaction.done;
  emitMessagesChanged();
}

export async function localAttachmentBlob(
  attachmentId: string,
): Promise<Blob | null> {
  const database = await getCortexDb();
  return (await database.get("mensagem_anexos", attachmentId))?.arquivo ?? null;
}

function conversationRecord(
  conversation: ConversationApi,
): ConversaLocalRecord {
  return {
    id: conversation.id,
    tipo: conversation.tipo,
    titulo: conversation.titulo,
    obraId: conversation.obraId,
    equipeId: conversation.equipeId,
    status: conversation.status,
    participantes: conversation.participantes,
    criadaEm: conversation.criadaEm,
    atualizadaEm: conversation.atualizadaEm,
    versaoEntidade: conversation.versao,
  };
}

async function addAttachmentHashes(
  plan: QueuedMessagePlan,
): Promise<QueuedMessagePlan> {
  const attachments = await Promise.all(
    plan.attachments.map(async (attachment) => ({
      ...attachment,
      sha256: attachment.arquivo
        ? await sha256Blob(attachment.arquivo)
        : null,
    })),
  );
  return { ...plan, attachments };
}

export async function sha256Blob(blob: Blob): Promise<string> {
  const digest = new Uint8Array(
    await crypto.subtle.digest("SHA-256", await blob.arrayBuffer()),
  );
  return Array.from(digest, (value) => value.toString(16).padStart(2, "0")).join(
    "",
  );
}

function formatBytes(bytes: number): string {
  return `${Math.round(bytes / 1024 / 1024)} MB`;
}

export function emitMessagesChanged(): void {
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(MESSAGES_CHANGED_EVENT));
  }
}
