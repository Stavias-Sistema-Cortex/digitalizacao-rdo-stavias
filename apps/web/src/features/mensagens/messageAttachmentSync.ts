import { getCortexDb } from "../../lib/db/cortexDb";
import type { LocalMessageAttachmentRecord } from "../../lib/db/db.types";
import {
  MessageApiError,
  type MessageAttachmentDto,
  uploadMessageAttachment,
} from "./messageApi";

const MAX_ATTACHMENTS_PER_RUN = 20;
const MAX_RETRY_DELAY_MS = 15 * 60_000;

export interface MessageAttachmentSyncSummary {
  attempted: number;
  uploaded: number;
  deferred: number;
  errors: number;
}

interface MessageAttachmentSyncDependencies {
  upload?: typeof uploadMessageAttachment;
  now?: () => Date;
}

export function attachmentRetryDelayMs(attempt: number): number {
  const safeAttempt = Math.max(1, Math.trunc(attempt));
  return Math.min(
    5_000 * 3 ** (safeAttempt - 1),
    MAX_RETRY_DELAY_MS,
  );
}

function errorMessage(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "Falha desconhecida ao enviar o anexo.";
}

function isPermanentError(error: unknown): boolean {
  if (!(error instanceof MessageApiError)) {
    return false;
  }
  if (error.status === 401 || error.status === 403) {
    return true;
  }
  return (
    error.status >= 400 &&
    error.status < 500 &&
    error.status !== 408 &&
    error.status !== 429
  );
}

function canonicalAttachment(
  local: LocalMessageAttachmentRecord,
  response: MessageAttachmentDto,
): LocalMessageAttachmentRecord {
  if (
    response.id !== local.id ||
    response.mensagemId !== local.mensagemId ||
    response.status !== "DISPONIVEL"
  ) {
    throw new Error("O servidor não confirmou o anexo enviado.");
  }

  return {
    ...local,
    clientAttachmentId: response.clientAttachmentId,
    nomeOriginal: response.nomeOriginal,
    nomeSeguro: response.nomeSeguro,
    mimeType: response.mimeType,
    tamanhoBytes: response.tamanhoBytes,
    hashSha256: response.hashSha256,
    status: response.status,
    ultimoErro: response.ultimoErro,
    criadoEm: response.criadoEm,
    atualizadoEm: response.atualizadoEm,
    disponivelEm: response.disponivelEm,
    versaoEntidade: response.versaoEntidade,
    syncStatus: "UPLOADED",
    proximaTentativaEm: null,
  };
}

async function saveAttachment(
  attachment: LocalMessageAttachmentRecord,
): Promise<void> {
  const database = await getCortexDb();
  await database.put("message_attachments", attachment);
}

export async function syncPendingMessageAttachments(
  dependencies: MessageAttachmentSyncDependencies = {},
): Promise<MessageAttachmentSyncSummary> {
  const upload = dependencies.upload ?? uploadMessageAttachment;
  const now = dependencies.now ?? (() => new Date());
  const database = await getCortexDb();
  const candidates = await database.getAllFromIndex(
    "message_attachments",
    "by-sync-status",
    "PENDING_UPLOAD",
  );
  const nowValue = now();
  const due = candidates
    .filter(
      (attachment) =>
        !attachment.proximaTentativaEm ||
        new Date(attachment.proximaTentativaEm).getTime() <= nowValue.getTime(),
    )
    .slice(0, MAX_ATTACHMENTS_PER_RUN);
  const summary: MessageAttachmentSyncSummary = {
    attempted: 0,
    uploaded: 0,
    deferred: 0,
    errors: 0,
  };

  for (const candidate of due) {
    const attemptTime = now().toISOString();
    const attempt = candidate.tentativas + 1;
    const uploading: LocalMessageAttachmentRecord = {
      ...candidate,
      syncStatus: "UPLOADING",
      tentativas: attempt,
      ultimaTentativaEm: attemptTime,
      proximaTentativaEm: null,
      ultimoErro: null,
      atualizadoEm: attemptTime,
    };
    await saveAttachment(uploading);
    summary.attempted += 1;

    try {
      if (!uploading.arquivo) {
        throw new MessageApiError(
          422,
          "O conteúdo local do anexo não está disponível.",
        );
      }
      const response = await upload(
        uploading.mensagemId,
        uploading.id,
        uploading.arquivo,
        uploading.nomeOriginal,
      );
      await saveAttachment(canonicalAttachment(uploading, response));
      summary.uploaded += 1;
    } catch (error: unknown) {
      const permanent = isPermanentError(error);
      const failureTime = now();
      const nextAttempt = permanent
        ? null
        : new Date(
            failureTime.getTime() + attachmentRetryDelayMs(attempt),
          ).toISOString();
      await saveAttachment({
        ...uploading,
        syncStatus: permanent ? "ERROR" : "PENDING_UPLOAD",
        ultimoErro: errorMessage(error),
        proximaTentativaEm: nextAttempt,
        atualizadoEm: failureTime.toISOString(),
      });
      if (permanent) {
        summary.errors += 1;
      } else {
        summary.deferred += 1;
      }
    }
  }

  return summary;
}
