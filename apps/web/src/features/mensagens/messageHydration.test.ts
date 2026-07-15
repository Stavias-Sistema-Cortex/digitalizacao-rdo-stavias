import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  closeCortexDb,
  CORTEX_DATABASE_NAME,
  getCortexDb,
} from "../../lib/db/cortexDb";
import {
  getLocalConversation,
  listLocalConversationParticipants,
} from "../../lib/db/conversationLocalRepository";
import {
  getLocalMessage,
  listLocalMessageAttachments,
} from "../../lib/db/messageLocalRepository";
import type { ConversationDto, MessageDto } from "./messageApi";
import {
  hydrateConversationPage,
  hydrateMessage,
} from "./messageHydration";
import { saveMessageOffline } from "./messageLocalService";

const NOW = "2026-07-15T15:30:00Z";

async function resetDatabase(): Promise<void> {
  await closeCortexDb();
  await deleteDB(CORTEX_DATABASE_NAME);
}

beforeEach(resetDatabase);
afterEach(resetDatabase);

function messageDto(overrides: Partial<MessageDto> = {}): MessageDto {
  return {
    id: "message-1",
    conversaId: "conversation-1",
    remetenteId: "user-1",
    remetenteNome: "Ana",
    clientMessageId: "client-message-1",
    texto: "Frente liberada",
    estado: "ENVIADA",
    enviadaClienteEm: "2026-07-15T15:29:59Z",
    criadaServidorEm: NOW,
    atualizadaEm: NOW,
    versaoEntidade: 1,
    referencias: [],
    anexos: [],
    recibos: [],
    ...overrides,
  };
}

function conversationDto(): ConversationDto {
  return {
    id: "conversation-1",
    tipo: "OBRA",
    titulo: "Execução da ponte",
    obraId: "obra-1",
    obraNome: "Ponte Norte",
    equipeId: "team-1",
    equipeNome: "Terraplenagem",
    criadoPor: "user-1",
    status: "ATIVA",
    ultimaAtividadeEm: NOW,
    criadoEm: "2026-07-15T14:00:00Z",
    atualizadoEm: NOW,
    versaoEntidade: 3,
    participantes: [
      {
        id: "participant-1",
        conversaId: "conversation-1",
        colaboradorId: "user-1",
        colaboradorNome: "Ana",
        papel: "ADMINISTRADOR",
        status: "ATIVO",
        entrouEm: "2026-07-15T14:00:00Z",
        saiuEm: null,
        ultimaLeituraEm: "2026-07-15T15:20:00Z",
        versaoEntidade: 1,
      },
    ],
    ultimaMensagem: messageDto(),
    naoLidas: 2,
  };
}

describe("hidratação local de Mensagens", () => {
  it("persiste a projeção real de conversa, participantes e última mensagem", async () => {
    await hydrateConversationPage({
      items: [conversationDto()],
      page: 0,
      size: 20,
      total: 1,
      hasMore: false,
    });

    expect(await getLocalConversation("conversation-1")).toEqual({
      id: "conversation-1",
      tipo: "OBRA",
      titulo: "Execução da ponte",
      obraId: "obra-1",
      obraNome: "Ponte Norte",
      equipeId: "team-1",
      equipeNome: "Terraplenagem",
      criadoPor: "user-1",
      status: "ATIVA",
      ultimaAtividadeEm: NOW,
      naoLidas: 2,
      criadoEm: "2026-07-15T14:00:00Z",
      atualizadoEm: NOW,
      versaoEntidade: 3,
    });
    expect(await listLocalConversationParticipants("conversation-1"))
      .toMatchObject([
        {
          id: "participant-1",
          colaboradorNome: "Ana",
          ultimaLeituraEm: "2026-07-15T15:20:00Z",
        },
      ]);
    expect(await getLocalMessage("message-1")).toMatchObject({
      texto: "Frente liberada",
      syncStatus: "SENT",
      criadaServidorEm: NOW,
    });
  });

  it("reconcilia resposta remota sem perder Blob nem estado de retry local", async () => {
    const saved = await saveMessageOffline({
      conversaId: "conversation-1",
      remetenteId: "user-1",
      remetenteNome: "Ana",
      texto: "Foto da frente",
      anexos: [
        {
          id: "attachment-1",
          clientAttachmentId: "client-attachment-1",
          nomeOriginal: "frente.jpg",
          mimeType: "image/jpeg",
          arquivo: new Blob(["imagem-real"], { type: "image/jpeg" }),
        },
      ],
    });
    const database = await getCortexDb();
    const localAttachment = (await listLocalMessageAttachments(saved.message.id))[0];
    await database.put("message_attachments", {
      ...localAttachment,
      syncStatus: "PENDING_UPLOAD",
      tentativas: 2,
      ultimaTentativaEm: "2026-07-15T15:25:00Z",
      proximaTentativaEm: "2026-07-15T15:35:00Z",
    });

    await hydrateMessage(messageDto({
      id: saved.message.id,
      clientMessageId: saved.message.clientMessageId,
      anexos: [
        {
          id: "attachment-1",
          mensagemId: saved.message.id,
          clientAttachmentId: "client-attachment-1",
          nomeOriginal: "frente.jpg",
          nomeSeguro: "frente.jpg",
          mimeType: "image/jpeg",
          tamanhoBytes: localAttachment.tamanhoBytes,
          hashSha256: localAttachment.hashSha256,
          status: "PENDENTE",
          ultimoErro: null,
          criadoEm: NOW,
          atualizadoEm: NOW,
          disponivelEm: null,
          versaoEntidade: 1,
        },
      ],
    }));

    const hydrated = (await listLocalMessageAttachments(saved.message.id))[0];
    expect(await hydrated.arquivo?.text()).toBe("imagem-real");
    expect(hydrated).toMatchObject({
      syncStatus: "PENDING_UPLOAD",
      tentativas: 2,
      ultimaTentativaEm: "2026-07-15T15:25:00Z",
      proximaTentativaEm: "2026-07-15T15:35:00Z",
    });
  });

  it("confirma anexo disponível e elimina agendamento de retry", async () => {
    const available = messageDto({
      anexos: [
        {
          id: "attachment-1",
          mensagemId: "message-1",
          clientAttachmentId: "client-attachment-1",
          nomeOriginal: "diario.pdf",
          nomeSeguro: "diario.pdf",
          mimeType: "application/pdf",
          tamanhoBytes: 12,
          hashSha256: "a".repeat(64),
          status: "DISPONIVEL",
          ultimoErro: null,
          criadoEm: NOW,
          atualizadoEm: NOW,
          disponivelEm: NOW,
          versaoEntidade: 2,
        },
      ],
    });

    await hydrateMessage(available);

    expect((await listLocalMessageAttachments("message-1"))[0]).toMatchObject({
      status: "DISPONIVEL",
      syncStatus: "UPLOADED",
      proximaTentativaEm: null,
      versaoEntidade: 2,
    });
  });
});
