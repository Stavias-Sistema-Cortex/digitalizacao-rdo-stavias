import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";
import { getCortexDb } from "../../lib/db/cortexDb";
import type { OutboxMutationRecord } from "../../lib/db/db.types";
import {
  assertCanonicalPayloadHash,
  isCanonicalOutboxMutation,
} from "../../lib/sync/mutationEnvelope";
import { emitMessagesChanged } from "./mensagensRepository";

interface StoredObjectUploadResponse {
  id: string;
  obraId: string | null;
  nomeOriginal: string;
  mediaType: string;
  tamanhoBytes: number;
  sha256: string;
  status: string;
  criadoEm: string;
  deduplicado: boolean;
}

export interface ObjectUploadSummary {
  pushed: number;
  applied: number;
  errors: number;
}

export async function processObjectUploads(
  limit = 20,
): Promise<ObjectUploadSummary> {
  const database = await getCortexDb();
  const candidates = (await database.getAll("outbox_mutations"))
    .filter(
      (mutation) =>
        mutation.status === "PENDING" &&
        mutation.transport === "OBJECT_UPLOAD",
    )
    .sort((left, right) =>
      left.criadaNoClienteEm.localeCompare(right.criadaNoClienteEm),
    )
    .slice(0, Math.max(0, limit));

  let applied = 0;
  let errors = 0;
  for (const mutation of candidates) {
    try {
      await processOneUpload(mutation);
      applied += 1;
    } catch (error: unknown) {
      errors += 1;
      await markUploadFailed(
        mutation,
        error instanceof Error
          ? error.message
          : "Falha desconhecida ao enviar o anexo.",
      );
    }
  }
  if (candidates.length > 0) {
    emitMessagesChanged();
  }
  return { pushed: candidates.length, applied, errors };
}

async function processOneUpload(
  mutation: OutboxMutationRecord,
): Promise<void> {
  if (isCanonicalOutboxMutation(mutation)) {
    await assertCanonicalPayloadHash(mutation);
  }
  const database = await getCortexDb();
  const attachment = await database.getFromIndex(
    "mensagem_anexos",
    "by-upload-mutation-id",
    mutation.clientMutationId,
  );
  if (!attachment?.arquivo || !attachment.sha256) {
    throw new Error("O arquivo local ou seu hash não está disponível.");
  }
  const conversation = await database.get(
    "mensagem_conversas",
    attachment.conversaId,
  );
  const timestamp = new Date().toISOString();
  const starting = database.transaction(
    ["outbox_mutations", "mensagem_anexos", "mensagens"],
    "readwrite",
  );
  const current = await starting
    .objectStore("outbox_mutations")
    .get(mutation.clientMutationId);
  if (!current || current.status !== "PENDING") {
    starting.abort();
    return;
  }
  await starting.objectStore("outbox_mutations").put({
    ...current,
    status: "SYNCING",
    tentativas: current.tentativas + 1,
    ultimaTentativaEm: timestamp,
    ultimoErro: null,
    updatedAt: timestamp,
  });
  await starting.objectStore("mensagem_anexos").put({
    ...attachment,
    syncStatus: "SINCRONIZANDO",
    ultimoErro: null,
    updatedAt: timestamp,
  });
  const message = await starting
    .objectStore("mensagens")
    .get(attachment.mensagemId);
  if (message) {
    await starting.objectStore("mensagens").put({
      ...message,
      syncStatus: "SINCRONIZANDO",
      ultimoErro: null,
      updatedAt: timestamp,
    });
  }
  await starting.done;

  const form = new FormData();
  form.append(
    "arquivo",
    attachment.arquivo,
    attachment.nome,
  );
  const params = new URLSearchParams();
  if (conversation?.obraId) {
    params.set("obraId", conversation.obraId);
  }
  const suffix = params.size > 0 ? `?${params.toString()}` : "";
  const response = await apiFetch(`/objetos${suffix}`, {
    method: "POST",
    body: form,
    timeoutMs: 60_000,
    connectionErrorMessage:
      "Não foi possível enviar o anexo. O arquivo continua salvo neste dispositivo.",
    timeoutErrorMessage:
      "O envio do anexo demorou demais. O arquivo continua salvo neste dispositivo.",
  });
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(body, response.status));
  }
  const uploaded = body as StoredObjectUploadResponse;
  verifyUploadIntegrity(
    uploaded,
    attachment.sha256,
    attachment.tamanhoBytes,
  );
  await applyUploadedObject(
    mutation.clientMutationId,
    uploaded.id,
    uploaded.sha256,
  );
}

async function applyUploadedObject(
  uploadMutationId: string,
  objectId: string,
  sha256: string,
): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["outbox_mutations", "mensagem_anexos"],
    "readwrite",
  );
  const outbox = transaction.objectStore("outbox_mutations");
  const attachments = transaction.objectStore("mensagem_anexos");
  const mutation = await outbox.get(uploadMutationId);
  const attachment = await attachments
    .index("by-upload-mutation-id")
    .get(uploadMutationId);
  if (!mutation || !attachment) {
    transaction.abort();
    throw new Error("O upload concluído não possui metadados locais.");
  }
  const timestamp = new Date().toISOString();
  await outbox.put({
    ...mutation,
    status: "SYNCED",
    ultimoErro: null,
    conflito: null,
    updatedAt: timestamp,
  });
  await attachments.put({
    ...attachment,
    objetoId: objectId,
    sha256,
    syncStatus: "SINCRONIZADO",
    ultimoErro: null,
    updatedAt: timestamp,
  });

  for (const dependent of await outbox.getAll()) {
    if (!dependent.dependsOnMutationIds?.includes(uploadMutationId)) {
      continue;
    }
    if (isCanonicalOutboxMutation(dependent)) {
      continue;
    }
    await outbox.put({
      ...dependent,
      payload: resolveUploadReference(
        dependent.payload,
        uploadMutationId,
        objectId,
        sha256,
      ),
      updatedAt: timestamp,
    });
  }
  await transaction.done;
}

async function markUploadFailed(
  mutation: OutboxMutationRecord,
  message: string,
): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["outbox_mutations", "mensagem_anexos", "mensagens"],
    "readwrite",
  );
  const timestamp = new Date().toISOString();
  const outboxStore = transaction.objectStore("outbox_mutations");
  const attachmentStore = transaction.objectStore("mensagem_anexos");
  const messageStore = transaction.objectStore("mensagens");
  const current = await outboxStore.get(mutation.clientMutationId);
  if (current) {
    await outboxStore.put({
      ...current,
      status: "ERROR",
      ultimoErro: message,
      updatedAt: timestamp,
    });
  }
  const attachment = await attachmentStore
    .index("by-upload-mutation-id")
    .get(mutation.clientMutationId);
  if (attachment) {
    await attachmentStore.put({
      ...attachment,
      syncStatus: "FALHOU",
      ultimoErro: message,
      updatedAt: timestamp,
    });
    const localMessage = await messageStore.get(attachment.mensagemId);
    if (localMessage) {
      await messageStore.put({
        ...localMessage,
        syncStatus: "FALHOU",
        ultimoErro: message,
        updatedAt: timestamp,
      });
    }
  }
  await transaction.done;
}

export function resolveUploadReference(
  payload: Record<string, unknown>,
  uploadMutationId: string,
  objectId: string,
  sha256: string,
): Record<string, unknown> {
  return replaceValue(payload) as Record<string, unknown>;

  function replaceValue(value: unknown): unknown {
    if (Array.isArray(value)) {
      return value.map(replaceValue);
    }
    if (value && typeof value === "object") {
      const record = value as Record<string, unknown>;
      if (record.uploadMutationId === uploadMutationId) {
        return { objetoId: objectId, sha256 };
      }
      return Object.fromEntries(
        Object.entries(record).map(([key, item]) => [key, replaceValue(item)]),
      );
    }
    return value;
  }
}

export function verifyUploadIntegrity(
  response: Pick<StoredObjectUploadResponse, "id" | "sha256" | "tamanhoBytes">,
  expectedSha256: string,
  expectedSize: number,
): void {
  if (
    !response.id ||
    response.sha256 !== expectedSha256 ||
    response.tamanhoBytes !== expectedSize
  ) {
    throw new Error(
      "A integridade do anexo enviado não foi confirmada pelo servidor.",
    );
  }
}
