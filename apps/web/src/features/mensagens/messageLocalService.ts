import { getCortexDb } from "../../lib/db/cortexDb";
import type {
  LocalMessageAttachmentRecord,
  LocalMessageRecord,
  LocalMessageReferenceRecord,
  OutboxMutationRecord,
} from "../../lib/db/db.types";

export interface MessageReferenceInput {
  id?: string;
  tipoObjeto: string;
  objetoId: string;
}

export interface MessageAttachmentInput {
  id?: string;
  clientAttachmentId?: string;
  nomeOriginal: string;
  mimeType?: string;
  arquivo: Blob;
}

export interface SaveMessageOfflineInput {
  conversaId: string;
  remetenteId: string | null;
  remetenteNome: string | null;
  texto: string | null;
  referencias?: MessageReferenceInput[];
  anexos?: MessageAttachmentInput[];
}

export interface SaveMessageOfflineResult {
  message: LocalMessageRecord;
  mutation: OutboxMutationRecord;
  references: LocalMessageReferenceRecord[];
  attachments: LocalMessageAttachmentRecord[];
}

function requiredText(value: string, field: string): string {
  const normalized = value.trim();
  if (!normalized) {
    throw new Error(`${field} é obrigatório.`);
  }
  return normalized;
}

function safeFilename(value: string): string {
  const basename = value.replaceAll("\\", "/").split("/").at(-1)?.trim();
  const sanitized = (basename || "anexo")
    .replace(/[^\p{L}\p{N}._ -]/gu, "_")
    .slice(0, 255);
  return sanitized || "anexo";
}

async function sha256(blob: Blob): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
  return [...new Uint8Array(digest)]
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
}

export async function saveMessageOffline(
  input: SaveMessageOfflineInput,
): Promise<SaveMessageOfflineResult> {
  const conversaId = requiredText(input.conversaId, "conversaId");
  const texto = input.texto?.trim() || null;
  const attachmentInputs = input.anexos ?? [];
  if (!texto && attachmentInputs.length === 0) {
    throw new Error("A mensagem precisa ter texto ou anexo.");
  }

  const timestamp = new Date().toISOString();
  const messageId = crypto.randomUUID();
  const clientMessageId = crypto.randomUUID();
  const clientMutationId = crypto.randomUUID();

  const references: LocalMessageReferenceRecord[] = (input.referencias ?? [])
    .map((reference) => ({
      id: reference.id?.trim() || crypto.randomUUID(),
      mensagemId: messageId,
      tipoObjeto: requiredText(reference.tipoObjeto, "tipoObjeto"),
      objetoId: requiredText(reference.objetoId, "objetoId"),
      obraId: null,
      criadoEm: null,
    }));

  const attachments: LocalMessageAttachmentRecord[] = await Promise.all(
    attachmentInputs.map(async (attachment) => {
      const nomeOriginal = requiredText(
        attachment.nomeOriginal,
        "nomeOriginal",
      );
      const mimeType =
        attachment.mimeType?.trim() ||
        attachment.arquivo.type.trim() ||
        "application/octet-stream";
      return {
        id: attachment.id?.trim() || crypto.randomUUID(),
        mensagemId: messageId,
        clientAttachmentId:
          attachment.clientAttachmentId?.trim() || crypto.randomUUID(),
        nomeOriginal,
        nomeSeguro: safeFilename(nomeOriginal),
        mimeType,
        tamanhoBytes: attachment.arquivo.size,
        hashSha256: await sha256(attachment.arquivo),
        status: "PENDENTE" as const,
        ultimoErro: null,
        criadoEm: timestamp,
        atualizadoEm: timestamp,
        disponivelEm: null,
        versaoEntidade: 1,
        arquivo: attachment.arquivo,
        syncStatus: "WAITING_MESSAGE" as const,
        tentativas: 0,
        ultimaTentativaEm: null,
        proximaTentativaEm: null,
      };
    }),
  );

  const message: LocalMessageRecord = {
    id: messageId,
    conversaId,
    remetenteId: input.remetenteId?.trim() || null,
    remetenteNome: input.remetenteNome?.trim() || null,
    clientMessageId,
    texto,
    estado: "PENDENTE",
    enviadaClienteEm: timestamp,
    criadaServidorEm: null,
    atualizadoEm: timestamp,
    versaoEntidade: null,
    recibos: [],
    syncStatus: "PENDING",
    ultimoErro: null,
  };

  const mutationPayload = {
    id: message.id,
    conversaId: message.conversaId,
    clientMessageId: message.clientMessageId,
    texto: message.texto,
    enviadaClienteEm: message.enviadaClienteEm,
    referencias: references.map((reference) => ({
      id: reference.id,
      tipoObjeto: reference.tipoObjeto,
      objetoId: reference.objetoId,
    })),
    anexos: attachments.map((attachment) => ({
      id: attachment.id,
      clientAttachmentId: attachment.clientAttachmentId,
      nomeOriginal: attachment.nomeOriginal,
      mimeType: attachment.mimeType,
      tamanhoBytes: attachment.tamanhoBytes,
      hashSha256: attachment.hashSha256,
    })),
  };
  const mutation: OutboxMutationRecord = {
    clientMutationId,
    entidadeTipo: "MENSAGEM",
    entidadeId: message.id,
    operacao: "CRIAR_MENSAGEM",
    baseVersao: null,
    payload: mutationPayload,
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: timestamp,
    updatedAt: timestamp,
  };

  const database = await getCortexDb();
  const transaction = database.transaction(
    [
      "messages",
      "message_references",
      "message_attachments",
      "outbox_mutations",
    ],
    "readwrite",
  );

  await transaction.objectStore("messages").add(message);
  await Promise.all(
    references.map((reference) =>
      transaction.objectStore("message_references").add(reference),
    ),
  );
  await Promise.all(
    attachments.map((attachment) =>
      transaction.objectStore("message_attachments").add(attachment),
    ),
  );
  await transaction.objectStore("outbox_mutations").add(mutation);
  await transaction.done;

  return { message, mutation, references, attachments };
}

export async function retryLocalMessage(messageId: string): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["messages", "outbox_mutations"],
    "readwrite",
  );
  const messageStore = transaction.objectStore("messages");
  const outboxStore = transaction.objectStore("outbox_mutations");
  const message = await messageStore.get(messageId);
  const mutations = await outboxStore.index("by-entity-id").getAll(messageId);
  const mutation = mutations.find(
    (candidate) =>
      candidate.entidadeTipo === "MENSAGEM" &&
      candidate.operacao === "CRIAR_MENSAGEM",
  );

  if (!message || !mutation) {
    transaction.abort();
    throw new Error("Mensagem local ou mutação de envio não encontrada.");
  }

  const timestamp = new Date().toISOString();
  await messageStore.put({
    ...message,
    syncStatus: "PENDING",
    ultimoErro: null,
    atualizadoEm: timestamp,
  });
  await outboxStore.put({
    ...mutation,
    status: "PENDING",
    ultimoErro: null,
    conflito: null,
    updatedAt: timestamp,
  });
  await transaction.done;
}
