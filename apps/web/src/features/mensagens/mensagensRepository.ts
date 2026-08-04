import { getCortexDb } from "../../lib/db/cortexDb";
import type {
  ConversaLocalRecord,
  MensagemAnexoLocalRecord,
  MensagemLocalRecord,
} from "../../lib/db/db.types";
import { getSession } from "../auth/authSession";
import type {
  ConversationApi,
  MessageApi,
} from "./mensagensApi";
import {
  buildQueuedConversation,
  buildQueuedMessage,
  type BuildQueuedConversationInput,
  type QueuedMessagePlan,
} from "./mensagensQueue";
import {
  buildConversationPreviews,
  type ConversationPreview,
} from "./mensagensView";
import { guardSyncTransaction } from "../../lib/sync/guardedSyncTransaction";
import { LOCAL_MUTATION_QUEUED_EVENT } from "../../lib/sync/localMutationCoordinator";
import {
  assertSyncSession,
  type SyncSessionGuard,
} from "../../lib/sync/syncSession";

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

/**
 * Cria a conversa no dispositivo e a coloca na fila de subida.
 *
 * <p>Era a única escrita de Mensagens que falava direto com o servidor, e por
 * isso a única que o campo não podia fazer: sem rede, o botão de nova conversa
 * ficava desabilitado. Agora ela segue o mesmo caminho de todo o resto —
 * escreve local, sobe depois — e a tela deixa de ter dois comportamentos
 * conforme o sinal.
 */
export async function queueConversation(
  input: Omit<BuildQueuedConversationInput, "autorId" | "autorNome">,
): Promise<ConversaLocalRecord> {
  const session = getSession();
  if (!session) {
    throw new Error("Abra a sessão protegida antes de criar conversas.");
  }
  const plan = buildQueuedConversation({
    ...input,
    autorId: session.colaboradorId,
    autorNome: session.nome,
  });
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["mensagem_conversas", "outbox_mutations"],
    "readwrite",
  );
  await transaction.objectStore("mensagem_conversas").add(plan.conversation);
  await transaction.objectStore("outbox_mutations").add(plan.mutation);
  await transaction.done;
  emitMessagesChanged();
  anunciarEscritaLocal();
  return plan.conversation;
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
  const transaction = database.transaction(
    ["mensagens", "mensagem_anexos", "outbox_mutations"],
    "readwrite",
  );

  await transaction.objectStore("mensagens").add(hashedPlan.message);
  for (const attachment of hashedPlan.attachments) {
    await transaction.objectStore("mensagem_anexos").add(attachment);
  }
  /*
   * Uma mensagem escrita numa conversa que ainda não subiu precisa esperar
   * por ela. Sem isso o servidor receberia a mensagem antes da conversa e a
   * recusaria por conversa inexistente — e a recusa travaria a fila inteira
   * atrás dela, que é o pior desfecho possível para quem apontou em campo.
   */
  const criacaoDaConversa = await transaction
    .objectStore("outbox_mutations")
    .get(input.conversaId);
  const dependeDaConversa =
    criacaoDaConversa?.entidadeTipo === "CONVERSA" &&
    criacaoDaConversa.operacao === "CRIAR_CONVERSA";
  const messageMutation = dependeDaConversa
    ? {
      ...hashedPlan.messageMutation,
      dependsOnMutationIds: [
        ...(hashedPlan.messageMutation.dependsOnMutationIds ?? []),
        criacaoDaConversa.clientMutationId,
      ],
    }
    : hashedPlan.messageMutation;
  for (const mutation of [
    ...hashedPlan.uploadMutations,
    messageMutation,
  ]) {
    await transaction.objectStore("outbox_mutations").add(mutation);
  }
  await transaction.done;
  emitMessagesChanged();
  /*
   * A mensagem entra na fila pela escrita direta, sem passar pelo coordenador
   * canônico — e por isso era a única escrita local que não avisava o
   * agendador. Quem enviava daqui era salvo pelo `syncNow` explícito da tela;
   * qualquer outro caminho ficava esperando a janela de trinta segundos. O
   * aviso é o contrato de toda escrita local: escreveu, o sync acorda.
   */
  anunciarEscritaLocal();
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
  guard?: SyncSessionGuard,
): Promise<void> {
  if (guard) assertSyncSession(guard);
  const database = await getCortexDb();
  if (guard) assertSyncSession(guard);
  const rawTransaction = database.transaction(
    [
      "mensagem_conversas",
      "mensagens",
      "mensagem_anexos",
      "outbox_mutations",
    ],
    "readwrite",
  );
  const guardedTransaction = guard
    ? guardSyncTransaction(rawTransaction, guard)
    : null;
  const transaction = guardedTransaction?.transaction ?? rawTransaction;
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
    /*
     * A conversa criada aqui e ainda não subida não está na resposta do
     * servidor — ele não a conhece. Apagá-la por isso destruiria a conversa,
     * as mensagens escritas nela e a própria fila que as levaria para cima,
     * tudo em silêncio, na primeira releitura. É a mesma regra da geometria:
     * resposta do servidor não apaga trabalho local que ainda não subiu.
     */
    const aguardandoSubida = new Set(
      (await transaction.objectStore("outbox_mutations").getAll())
        .filter(
          (mutation) =>
            mutation.entidadeTipo === "CONVERSA" &&
            mutation.operacao === "CRIAR_CONVERSA",
        )
        .map((mutation) => mutation.entidadeId),
    );
    for (const local of await conversationStore.getAll()) {
      if (authorizedIds.has(local.id) || aguardandoSubida.has(local.id)) {
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
  if (guardedTransaction) {
    await guardedTransaction.complete();
  } else {
    await transaction.done;
  }
  emitMessagesChanged();
}

export async function storeServerMessages(
  messages: MessageApi[],
  guard?: SyncSessionGuard,
): Promise<void> {
  if (guard) assertSyncSession(guard);
  const database = await getCortexDb();
  if (guard) assertSyncSession(guard);
  const rawTransaction = database.transaction(
    ["mensagens", "mensagem_anexos"],
    "readwrite",
  );
  const guardedTransaction = guard
    ? guardSyncTransaction(rawTransaction, guard)
    : null;
  const transaction = guardedTransaction?.transaction ?? rawTransaction;
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
  if (guardedTransaction) {
    await guardedTransaction.complete();
  } else {
    await transaction.done;
  }
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
  // A mensagem voltou para a fila: o mesmo aviso vale aqui.
  anunciarEscritaLocal();
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

/** Avisa o agendador que há trabalho novo na fila, como toda escrita local. */
function anunciarEscritaLocal(): void {
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(LOCAL_MUTATION_QUEUED_EVENT));
  }
}
