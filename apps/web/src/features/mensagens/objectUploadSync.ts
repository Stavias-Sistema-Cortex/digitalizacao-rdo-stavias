import {
  apiFetch,
  apiError,
  readResponseBody,
} from "../../lib/api/apiClient";
import { getCortexDb } from "../../lib/db/cortexDb";
import type { OutboxMutationRecord } from "../../lib/db/db.types";
import {
  assertCanonicalPayloadHash,
  isCanonicalOutboxMutation,
} from "../../lib/sync/mutationEnvelope";
import {
  assertSyncSession,
  captureOnlineSyncSession,
  type SyncSessionGuard,
} from "../../lib/sync/syncSession";
import { mutationAfterRetryScheduled } from "../../lib/sync/automaticSyncRetryStorage";
import { classifyAutomaticRequestFailure } from "../../lib/sync/automaticRequestFailure";
import { guardSyncTransaction } from "../../lib/sync/guardedSyncTransaction";
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

export const CANONICAL_UPLOAD_REFERENCE_BLOCKED =
  "CANONICAL_UPLOAD_REFERENCE_REQUIRES_REPLACEMENT";

export async function processObjectUploads(
  limit = 20,
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<ObjectUploadSummary> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);
  const candidates = (await database.getAll("outbox_mutations"))
    .filter(
      (mutation) =>
        mutation.status === "PENDING" &&
        mutation.transport === "OBJECT_UPLOAD" &&
        (!mutation.nextAttemptAt ||
          !Number.isFinite(Date.parse(mutation.nextAttemptAt)) ||
          Date.parse(mutation.nextAttemptAt) <= Date.now()),
    )
    .sort((left, right) =>
      left.criadaNoClienteEm.localeCompare(right.criadaNoClienteEm),
    )
    .slice(0, Math.max(0, limit));

  let applied = 0;
  let errors = 0;
  for (const mutation of candidates) {
    try {
      await processOneUpload(mutation, database, guard);
      applied += 1;
    } catch (error: unknown) {
      // A session switch must never turn into a write in the new namespace.
      assertSyncSession(guard);
      errors += 1;
      const failure = classifyAutomaticRequestFailure(error);
      await markUploadFailed(
        database,
        mutation,
        failure,
        guard,
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
  database: Awaited<ReturnType<typeof getCortexDb>>,
  guard: SyncSessionGuard,
): Promise<void> {
  assertSyncSession(guard);
  if (isCanonicalOutboxMutation(mutation)) {
    await assertCanonicalPayloadHash(mutation);
  }
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
  const guardedStarting = guardSyncTransaction(
    database.transaction(
      ["outbox_mutations", "mensagem_anexos", "mensagens"],
      "readwrite",
    ),
    guard,
  );
  const starting = guardedStarting.transaction;
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
  await guardedStarting.complete();

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
  assertSyncSession(guard);
  const body = await readResponseBody(response);
  assertSyncSession(guard);
  if (!response.ok) {
    throw apiError(body, response.status);
  }
  const uploaded = body as StoredObjectUploadResponse;
  verifyUploadIntegrity(
    uploaded,
    attachment.sha256,
    attachment.tamanhoBytes,
  );
  await applyUploadedObject(
    database,
    mutation.clientMutationId,
    uploaded.id,
    uploaded.sha256,
    guard,
  );
}

async function applyUploadedObject(
  database: Awaited<ReturnType<typeof getCortexDb>>,
  uploadMutationId: string,
  objectId: string,
  sha256: string,
  guard: SyncSessionGuard,
): Promise<void> {
  assertSyncSession(guard);
  const guardedTransaction = guardSyncTransaction(
    database.transaction(
      ["outbox_mutations", "mensagem_anexos"],
      "readwrite",
    ),
    guard,
  );
  const transaction = guardedTransaction.transaction;
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
    await outbox.put(
      dependentAfterUploadResolution(
        dependent,
        uploadMutationId,
        objectId,
        sha256,
        timestamp,
      ),
    );
  }
  await guardedTransaction.complete();
}

export function dependentAfterUploadResolution(
  dependent: OutboxMutationRecord,
  uploadMutationId: string,
  objectId: string,
  sha256: string,
  timestamp: string,
): OutboxMutationRecord {
  if (isCanonicalOutboxMutation(dependent)) {
    return {
      ...dependent,
      blockedReason: CANONICAL_UPLOAD_REFERENCE_BLOCKED,
      ultimoErro:
        "O anexo foi enviado; a mutação canônica aguarda um envelope substituto rastreável.",
      updatedAt: timestamp,
    };
  }
  return {
    ...dependent,
    payload: resolveUploadReference(
      dependent.payload,
      uploadMutationId,
      objectId,
      sha256,
    ),
    updatedAt: timestamp,
  };
}

async function markUploadFailed(
  database: Awaited<ReturnType<typeof getCortexDb>>,
  mutation: OutboxMutationRecord,
  failure: ReturnType<typeof classifyAutomaticRequestFailure>,
  guard: SyncSessionGuard,
): Promise<void> {
  assertSyncSession(guard);
  const guardedTransaction = guardSyncTransaction(
    database.transaction(
      ["outbox_mutations", "mensagem_anexos", "mensagens"],
      "readwrite",
    ),
    guard,
  );
  const transaction = guardedTransaction.transaction;
  const timestamp = new Date().toISOString();
  const outboxStore = transaction.objectStore("outbox_mutations");
  const attachmentStore = transaction.objectStore("mensagem_anexos");
  const messageStore = transaction.objectStore("mensagens");
  const current = await outboxStore.get(mutation.clientMutationId);
  if (current) {
    await outboxStore.put(
      failure.retryable
        ? mutationAfterRetryScheduled(current, {
            safeCode: failure.safeCode,
            message: failure.message,
            now: Date.parse(timestamp),
          })
        : {
            ...current,
            status: "REJECTED",
            retryAttempt: current.retryAttempt ?? 0,
            nextAttemptAt: null,
            lastSafeCode: failure.safeCode,
            blockedReason: failure.safeCode,
            ultimoErro: failure.message,
            updatedAt: timestamp,
          },
    );
  }
  const attachment = await attachmentStore
    .index("by-upload-mutation-id")
    .get(mutation.clientMutationId);
  if (attachment) {
    await attachmentStore.put({
      ...attachment,
      syncStatus: failure.retryable ? "NA_FILA" : "FALHOU",
      ultimoErro: failure.message,
      updatedAt: timestamp,
    });
    const localMessage = await messageStore.get(attachment.mensagemId);
    if (localMessage) {
      await messageStore.put({
        ...localMessage,
        syncStatus: failure.retryable ? "NA_FILA" : "FALHOU",
        ultimoErro: failure.message,
        updatedAt: timestamp,
      });
    }
  }
  await guardedTransaction.complete();
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
