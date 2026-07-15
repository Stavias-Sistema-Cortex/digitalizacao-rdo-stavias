import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  closeCortexDb,
  CORTEX_DATABASE_NAME,
  CORTEX_DATABASE_VERSION,
  getCortexDb,
} from "../../lib/db/cortexDb";
import { getOutboxMutation } from "../../lib/db/outboxRepository";
import {
  getLocalMessage,
  listLocalMessageAttachments,
  listLocalMessageReferences,
} from "../../lib/db/messageLocalRepository";
import {
  retryLocalMessage,
  saveMessageOffline,
} from "./messageLocalService";

async function resetDatabase(): Promise<void> {
  await closeCortexDb();
  await deleteDB(CORTEX_DATABASE_NAME);
}

beforeEach(resetDatabase);
afterEach(resetDatabase);

describe("migração local de Mensagens", () => {
  it("cria stores e índices aditivos na versão atual", async () => {
    const database = await getCortexDb();

    expect(database.version).toBe(CORTEX_DATABASE_VERSION);
    expect([...database.objectStoreNames]).toEqual(
      expect.arrayContaining([
        "conversations",
        "conversation_participants",
        "messages",
        "message_references",
        "message_attachments",
        "rdos",
        "outbox_mutations",
      ]),
    );

    const transaction = database.transaction(
      ["messages", "message_attachments"],
      "readonly",
    );
    expect([
      ...transaction.objectStore("messages").indexNames,
    ]).toEqual(
      expect.arrayContaining([
        "by-conversation-order",
        "by-client-message-id",
        "by-sync-status",
      ]),
    );
    expect([
      ...transaction.objectStore("message_attachments").indexNames,
    ]).toContain("by-message-id");
    await transaction.done;
  });
});

describe("saveMessageOffline", () => {
  it("persiste conteúdo, projeção otimista e outbox com os mesmos IDs estáveis", async () => {
    const result = await saveMessageOffline({
      conversaId: "conversation-1",
      remetenteId: "user-1",
      remetenteNome: "Ana",
      texto: "Frente liberada",
      referencias: [
        {
          id: "reference-1",
          tipoObjeto: "OBRA",
          objetoId: "obra-1",
        },
      ],
      anexos: [
        {
          id: "attachment-1",
          clientAttachmentId: "client-attachment-1",
          nomeOriginal: "relatorio.pdf",
          mimeType: "application/pdf",
          arquivo: new Blob(["%PDF-1.7"], {
            type: "application/pdf",
          }),
        },
      ],
    });

    const message = await getLocalMessage(result.message.id);
    const mutation = await getOutboxMutation(
      result.mutation.clientMutationId,
    );
    const references = await listLocalMessageReferences(result.message.id);
    const attachments = await listLocalMessageAttachments(result.message.id);

    expect(message).toMatchObject({
      id: result.message.id,
      conversaId: "conversation-1",
      clientMessageId: result.message.clientMessageId,
      texto: "Frente liberada",
      syncStatus: "PENDING",
    });
    expect(mutation).toMatchObject({
      entidadeTipo: "MENSAGEM",
      entidadeId: result.message.id,
      operacao: "CRIAR_MENSAGEM",
      status: "PENDING",
      baseVersao: null,
    });
    expect(mutation?.payload).toMatchObject({
      id: result.message.id,
      conversaId: "conversation-1",
      clientMessageId: result.message.clientMessageId,
      anexos: [
        {
          id: "attachment-1",
          clientAttachmentId: "client-attachment-1",
          nomeOriginal: "relatorio.pdf",
        },
      ],
    });
    expect(JSON.stringify(mutation?.payload)).not.toContain("arquivo");
    expect(references).toHaveLength(1);
    expect(attachments).toHaveLength(1);
    expect(attachments[0].arquivo).toBeInstanceOf(Blob);
    expect(await attachments[0].arquivo?.text()).toBe("%PDF-1.7");
    expect(attachments[0].hashSha256).toMatch(/^[a-f0-9]{64}$/);
    expect(attachments[0].syncStatus).toBe("WAITING_MESSAGE");
  });

  it("rejeita mensagem vazia antes de gravar a outbox", async () => {
    await expect(
      saveMessageOffline({
        conversaId: "conversation-1",
        remetenteId: null,
        remetenteNome: null,
        texto: "   ",
        referencias: [],
        anexos: [],
      }),
    ).rejects.toThrow("texto ou anexo");

    const database = await getCortexDb();
    expect(await database.count("messages")).toBe(0);
    expect(await database.count("outbox_mutations")).toBe(0);
  });
});

describe("retryLocalMessage", () => {
  it("reabre a mesma mutação sem trocar IDs idempotentes", async () => {
    const saved = await saveMessageOffline({
      conversaId: "conversation-1",
      remetenteId: "user-1",
      remetenteNome: "Ana",
      texto: "Reenvio",
      referencias: [],
      anexos: [],
    });
    const database = await getCortexDb();
    await database.put("messages", {
      ...saved.message,
      syncStatus: "ERROR",
      ultimoErro: "Rede indisponível",
    });
    await database.put("outbox_mutations", {
      ...saved.mutation,
      status: "ERROR",
      tentativas: 2,
      ultimoErro: "Rede indisponível",
    });

    await retryLocalMessage(saved.message.id);

    expect(await getLocalMessage(saved.message.id)).toMatchObject({
      id: saved.message.id,
      clientMessageId: saved.message.clientMessageId,
      syncStatus: "PENDING",
      ultimoErro: null,
    });
    expect(
      await getOutboxMutation(saved.mutation.clientMutationId),
    ).toMatchObject({
      clientMutationId: saved.mutation.clientMutationId,
      entidadeId: saved.message.id,
      status: "PENDING",
      ultimoErro: null,
    });
  });
});
