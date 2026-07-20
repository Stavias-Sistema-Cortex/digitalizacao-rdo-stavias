import { getCortexDb } from "./cortexDb";
import type { RdoAttachmentRecord } from "./db.types";

export async function putRdoAttachment(
  attachment: RdoAttachmentRecord,
): Promise<void> {
  const database = await getCortexDb();

  await database.put("rdo_attachments", attachment);
}

export async function getRdoAttachment(
  id: string,
): Promise<RdoAttachmentRecord | undefined> {
  const database = await getCortexDb();

  return database.get("rdo_attachments", id);
}

export async function listRdoAttachments(
  rdoId: string,
): Promise<RdoAttachmentRecord[]> {
  if (!rdoId.trim()) {
    return [];
  }

  const database = await getCortexDb();
  const attachments = await database.getAllFromIndex(
    "rdo_attachments",
    "by-rdo-id",
    rdoId,
  );

  return attachments
    .filter((attachment) => !attachment.removedAt)
    .sort((left, right) =>
      left.createdAt.localeCompare(right.createdAt),
    );
}

export async function listRdoAttachmentsByObra(
  obraId: string,
): Promise<RdoAttachmentRecord[]> {
  if (!obraId.trim()) {
    return [];
  }

  const database = await getCortexDb();
  const attachments = await database.getAllFromIndex(
    "rdo_attachments",
    "by-obra-id",
    obraId,
  );

  return attachments
    .filter((attachment) => !attachment.removedAt)
    .sort((left, right) =>
      right.createdAt.localeCompare(left.createdAt),
    );
}

export async function listAllRdoAttachments(): Promise<
  RdoAttachmentRecord[]
> {
  const database = await getCortexDb();
  const attachments = await database.getAllFromIndex(
    "rdo_attachments",
    "by-created-at",
  );

  return attachments
    .filter((attachment) => !attachment.removedAt)
    .sort((left, right) =>
      right.createdAt.localeCompare(left.createdAt),
    );
}

export async function markRdoAttachmentRemoved(
  id: string,
  removedAt = new Date().toISOString(),
): Promise<RdoAttachmentRecord | null> {
  const database = await getCortexDb();
  const attachment = await database.get("rdo_attachments", id);

  if (!attachment) {
    return null;
  }

  const removed: RdoAttachmentRecord = {
    ...attachment,
    removedAt,
    syncStatus:
      attachment.syncStatus === "SYNCED"
        ? "PENDING_SYNC"
        : attachment.syncStatus,
    updatedAt: removedAt,
  };

  await database.put("rdo_attachments", removed);

  return removed;
}

export function toAttachmentSyncPayload(
  attachment: RdoAttachmentRecord,
): Record<string, unknown> {
  return {
    id: attachment.id,
    rdoId: attachment.rdoId,
    obraId: attachment.obraId,
    tipo: attachment.tipo,
    nome: attachment.nome,
    nomeOriginal: attachment.nomeOriginal,
    mimeType: attachment.mimeType,
    tamanhoOriginalBytes: attachment.tamanhoOriginalBytes,
    tamanhoComprimidoBytes: attachment.tamanhoComprimidoBytes,
    tamanhoBytes: attachment.tamanhoBytes,
    syncStatus: attachment.syncStatus,
    createdAt: attachment.createdAt,
    updatedAt: attachment.updatedAt,
    removedAt: attachment.removedAt,
    metadata: attachment.metadata,
  };
}
