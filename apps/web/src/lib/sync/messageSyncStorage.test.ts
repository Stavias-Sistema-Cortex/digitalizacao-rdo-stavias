import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { saveMessageOffline } from "../../features/mensagens/messageLocalService";
import {
  closeCortexDb,
  CORTEX_DATABASE_NAME,
  getCortexDb,
  initializeCortexDb,
} from "../db/cortexDb";
import { getOutboxMutation } from "../db/outboxRepository";
import {
  getLocalMessage,
  listLocalMessageAttachments,
  listLocalMessageReferences,
  listLocalMessages,
} from "../db/messageLocalRepository";
import {
  applyPushResultAtomically,
  applyPulledEventsAtomically,
  markMutationAsSyncing,
  recoverInterruptedMutations,
  returnMutationToPending,
} from "./syncStorage";

async function resetDatabase(): Promise<void> {
  await closeCortexDb();
  await deleteDB(CORTEX_DATABASE_NAME);
}

beforeEach(resetDatabase);
afterEach(resetDatabase);

async function savedMessage() {
  return saveMessageOffline({
    conversaId: "conversation-1",
    remetenteId: "user-1",
    remetenteNome: "Ana local",
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
        nomeOriginal: "foto.jpg",
        mimeType: "image/jpeg",
        arquivo: new Blob([new Uint8Array([0xff, 0xd8, 0xff, 1])], {
          type: "image/jpeg",
        }),
      },
    ],
  });
}

describe("sync local de mensagem", () => {
  it("recupera SYNCING interrompido para PENDING sem perder o Blob", async () => {
    const saved = await savedMessage();

    await markMutationAsSyncing(saved.mutation);
    expect(await getLocalMessage(saved.message.id)).toMatchObject({
      syncStatus: "SYNCING",
    });

    await recoverInterruptedMutations();

    expect(await getLocalMessage(saved.message.id)).toMatchObject({
      syncStatus: "PENDING",
    });
    expect(
      await getOutboxMutation(saved.mutation.clientMutationId),
    ).toMatchObject({ status: "PENDING" });
    const attachments = await listLocalMessageAttachments(saved.message.id);
    expect(await attachments[0].arquivo?.arrayBuffer()).toBeTruthy();
    expect(attachments[0].syncStatus).toBe("WAITING_MESSAGE");
  });

  it("reconcilia payload canônico sem duplicar e preserva o arquivo local", async () => {
    const saved = await savedMessage();
    await markMutationAsSyncing(saved.mutation);

    await applyPushResultAtomically({
      clientMutationId: saved.mutation.clientMutationId,
      status: "APLICADA",
      entidadeTipo: "MENSAGEM",
      entidadeId: saved.message.id,
      eventoServidorCommitSeq: 81,
      resultado: {
        id: saved.message.id,
        conversaId: "conversation-1",
        remetenteId: "user-1",
        remetenteNome: "Ana canônica",
        clientMessageId: saved.message.clientMessageId,
        texto: "Frente liberada",
        estado: "ENVIADA",
        enviadaClienteEm: saved.message.enviadaClienteEm,
        criadaServidorEm: "2026-07-15T14:30:00Z",
        atualizadaEm: "2026-07-15T14:30:00Z",
        versaoEntidade: 1,
        referencias: [
          {
            id: "reference-1",
            mensagemId: saved.message.id,
            tipoObjeto: "OBRA",
            objetoId: "obra-1",
            obraId: "obra-1",
            criadoEm: "2026-07-15T14:30:00Z",
          },
        ],
        anexos: [
          {
            id: "attachment-1",
            mensagemId: saved.message.id,
            clientAttachmentId: "client-attachment-1",
            nomeOriginal: "foto.jpg",
            nomeSeguro: "foto.jpg",
            mimeType: "image/jpeg",
            tamanhoBytes: 4,
            hashSha256:
              "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            status: "PENDENTE",
            ultimoErro: null,
            criadoEm: "2026-07-15T14:30:00Z",
            atualizadoEm: "2026-07-15T14:30:00Z",
            disponivelEm: null,
            versaoEntidade: 1,
          },
        ],
        recibos: [],
      },
    });

    const database = await getCortexDb();
    expect(await database.count("messages")).toBe(1);
    expect(await listLocalMessages("conversation-1")).toHaveLength(1);
    expect(await getLocalMessage(saved.message.id)).toMatchObject({
      remetenteNome: "Ana canônica",
      estado: "ENVIADA",
      syncStatus: "SENT",
      criadaServidorEm: "2026-07-15T14:30:00Z",
      versaoEntidade: 1,
    });
    expect(await listLocalMessageReferences(saved.message.id)).toMatchObject([
      { id: "reference-1", obraId: "obra-1" },
    ]);
    const attachments = await listLocalMessageAttachments(saved.message.id);
    expect(attachments).toHaveLength(1);
    expect(attachments[0].arquivo).toBeInstanceOf(Blob);
    expect(attachments[0].syncStatus).toBe("PENDING_UPLOAD");
    expect(
      await getOutboxMutation(saved.mutation.clientMutationId),
    ).toMatchObject({ status: "SYNCED" });
  });

  it("mantém falha da mensagem recuperável e retorno de rede volta a PENDING", async () => {
    const saved = await savedMessage();
    await markMutationAsSyncing(saved.mutation);
    await applyPushResultAtomically({
      clientMutationId: saved.mutation.clientMutationId,
      status: "ERRO",
      entidadeTipo: "MENSAGEM",
      entidadeId: saved.message.id,
      erro: "Servidor indisponível",
    });

    expect(await getLocalMessage(saved.message.id)).toMatchObject({
      syncStatus: "ERROR",
      ultimoErro: "Servidor indisponível",
    });

    await returnMutationToPending(
      saved.mutation.clientMutationId,
      "Falha de rede",
    );

    expect(await getLocalMessage(saved.message.id)).toMatchObject({
      syncStatus: "PENDING",
      ultimoErro: "Falha de rede",
    });
  });

  it("projeta MENSAGEM_ENVIADA do pull em ordem e sem duplicar", async () => {
    await initializeCortexDb();
    const database = await getCortexDb();
    await database.put("conversations", {
      id: "conversation-1",
      tipo: "OBRA",
      titulo: "Obra Norte",
      obraId: "obra-1",
      equipeId: null,
      criadoPor: "user-1",
      status: "ATIVA",
      ultimaAtividadeEm: "2026-07-15T14:00:00Z",
      criadoEm: "2026-07-15T14:00:00Z",
      atualizadoEm: "2026-07-15T14:00:00Z",
      versaoEntidade: 1,
    });
    const event = {
      commitSeq: 91,
      eventoId: "event-91",
      tipoEvento: "MENSAGEM_ENVIADA",
      entidadeTipo: "MENSAGEM",
      entidadeId: "remote-message-1",
      ocorridoEmUtc: "2026-07-15T14:31:00Z",
      criadoEmUtc: "2026-07-15T14:31:00Z",
      versaoEntidade: 1,
      payload: {
        afterState: {
          id: "remote-message-1",
          conversaId: "conversation-1",
          remetenteId: "user-2",
          remetenteNome: "Bruno",
          clientMessageId: "remote-client-1",
          texto: "Cheguei à frente",
          estado: "ENVIADA",
          enviadaClienteEm: "2026-07-15T14:30:59Z",
          criadaServidorEm: "2026-07-15T14:31:00Z",
          atualizadaEm: "2026-07-15T14:31:00Z",
          referencias: [],
          anexos: [],
          recibos: [],
        },
      },
    };

    expect(await applyPulledEventsAtomically([event], 91)).toBe(91);
    expect(await applyPulledEventsAtomically([event], 91)).toBe(91);

    expect(await database.count("messages")).toBe(1);
    expect(await getLocalMessage("remote-message-1")).toMatchObject({
      conversaId: "conversation-1",
      remetenteNome: "Bruno",
      syncStatus: "SENT",
      versaoEntidade: 1,
    });
    expect(await database.get("conversations", "conversation-1"))
      .toMatchObject({
        ultimaAtividadeEm: "2026-07-15T14:31:00Z",
      });
  });
});
