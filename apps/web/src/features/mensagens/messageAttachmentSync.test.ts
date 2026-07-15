import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  closeCortexDb,
  CORTEX_DATABASE_NAME,
  getCortexDb,
} from "../../lib/db/cortexDb";
import type { LocalMessageAttachmentRecord } from "../../lib/db/db.types";
import { MessageApiError } from "./messageApi";
import {
  attachmentRetryDelayMs,
  syncPendingMessageAttachments,
} from "./messageAttachmentSync";

const NOW = "2026-07-15T15:00:00.000Z";

async function resetDatabase(): Promise<void> {
  await closeCortexDb();
  await deleteDB(CORTEX_DATABASE_NAME);
}

beforeEach(resetDatabase);
afterEach(resetDatabase);

function attachment(): LocalMessageAttachmentRecord {
  return {
    id: "attachment-1",
    mensagemId: "message-1",
    clientAttachmentId: "client-attachment-1",
    nomeOriginal: "foto.jpg",
    nomeSeguro: "foto.jpg",
    mimeType: "image/jpeg",
    tamanhoBytes: 4,
    hashSha256: "a".repeat(64),
    status: "PENDENTE",
    ultimoErro: null,
    criadoEm: NOW,
    atualizadoEm: NOW,
    disponivelEm: null,
    versaoEntidade: 1,
    arquivo: new Blob([new Uint8Array([0xff, 0xd8, 0xff, 1])], {
      type: "image/jpeg",
    }),
    syncStatus: "PENDING_UPLOAD",
    tentativas: 0,
    ultimaTentativaEm: null,
    proximaTentativaEm: null,
  };
}

async function seedAttachment(
  overrides: Partial<LocalMessageAttachmentRecord> = {},
): Promise<void> {
  const database = await getCortexDb();
  await database.put("message_attachments", {
    ...attachment(),
    ...overrides,
  });
}

describe("attachmentRetryDelayMs", () => {
  it("aplica backoff exponencial com teto", () => {
    expect(attachmentRetryDelayMs(1)).toBe(5_000);
    expect(attachmentRetryDelayMs(2)).toBe(15_000);
    expect(attachmentRetryDelayMs(3)).toBe(45_000);
    expect(attachmentRetryDelayMs(20)).toBe(15 * 60_000);
  });
});

describe("syncPendingMessageAttachments", () => {
  it("envia Blob autenticável e confirma estado canônico idempotente", async () => {
    await seedAttachment();
    const upload = vi.fn().mockResolvedValue({
      id: "attachment-1",
      mensagemId: "message-1",
      clientAttachmentId: "client-attachment-1",
      nomeOriginal: "foto.jpg",
      nomeSeguro: "foto.jpg",
      mimeType: "image/jpeg",
      tamanhoBytes: 4,
      hashSha256: "a".repeat(64),
      status: "DISPONIVEL",
      ultimoErro: null,
      criadoEm: NOW,
      atualizadoEm: NOW,
      disponivelEm: NOW,
      versaoEntidade: 2,
    });

    const summary = await syncPendingMessageAttachments({
      upload,
      now: () => new Date(NOW),
    });

    expect(summary).toEqual({ attempted: 1, uploaded: 1, deferred: 0, errors: 0 });
    expect(upload).toHaveBeenCalledWith(
      "message-1",
      "attachment-1",
      expect.any(Blob),
      "foto.jpg",
    );
    const stored = await (await getCortexDb()).get(
      "message_attachments",
      "attachment-1",
    );
    expect(stored).toMatchObject({
      status: "DISPONIVEL",
      syncStatus: "UPLOADED",
      versaoEntidade: 2,
      tentativas: 1,
      proximaTentativaEm: null,
    });
    expect(stored?.arquivo).toBeInstanceOf(Blob);
  });

  it("mantém rede e 5xx recuperáveis com backoff sem perder conteúdo", async () => {
    await seedAttachment();
    const upload = vi.fn().mockRejectedValue(new Error("Falha de rede"));

    const summary = await syncPendingMessageAttachments({
      upload,
      now: () => new Date(NOW),
    });

    expect(summary).toMatchObject({ attempted: 1, deferred: 1, errors: 0 });
    const stored = await (await getCortexDb()).get(
      "message_attachments",
      "attachment-1",
    );
    expect(stored).toMatchObject({
      syncStatus: "PENDING_UPLOAD",
      tentativas: 1,
      proximaTentativaEm: "2026-07-15T15:00:05.000Z",
      ultimoErro: "Falha de rede",
    });
    expect(stored?.arquivo).toBeInstanceOf(Blob);
  });

  it("trata 401/403 como permanentes e respeita próxima tentativa futura", async () => {
    await seedAttachment();
    const forbiddenUpload = vi
      .fn()
      .mockRejectedValue(new MessageApiError(403, "Acesso negado"));

    expect(
      await syncPendingMessageAttachments({
        upload: forbiddenUpload,
        now: () => new Date(NOW),
      }),
    ).toMatchObject({ attempted: 1, errors: 1 });
    expect(
      await (await getCortexDb()).get(
        "message_attachments",
        "attachment-1",
      ),
    ).toMatchObject({
      syncStatus: "ERROR",
      proximaTentativaEm: null,
      ultimoErro: "Acesso negado",
    });

    await seedAttachment({
      proximaTentativaEm: "2026-07-15T15:10:00.000Z",
    });
    const laterUpload = vi.fn();
    expect(
      await syncPendingMessageAttachments({
        upload: laterUpload,
        now: () => new Date(NOW),
      }),
    ).toEqual({ attempted: 0, uploaded: 0, deferred: 0, errors: 0 });
    expect(laterUpload).not.toHaveBeenCalled();
  });
});
