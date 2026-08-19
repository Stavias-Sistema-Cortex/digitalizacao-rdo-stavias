import "fake-indexeddb/auto";

import { openDB } from "idb";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  getSession,
  setSession,
} from "../auth/authSession";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import {
  CORTEX_DATABASE_VERSION,
  getCortexDb,
} from "../../lib/db/cortexDb";
import { captureOnlineSyncSession } from "../../lib/sync/syncSession";
import { listReadyPendingOutboxMutations } from "../../lib/db/outboxRepository";
import {
  confirmarPreferenciaDaConversaSincronizada,
  gravarPreferenciaDaConversa,
  listLocalConversations,
  listLocalMessages,
  queueMessage,
  reserveMessagingRequestOrdinal,
  searchLocalMessages,
  storeServerConversations,
  storeServerMessages,
} from "./mensagensRepository";

describe("mensagens IndexedDB repository", () => {
  let ownerId: string;

  beforeEach(() => {
    vi.stubGlobal("BroadcastChannel", undefined);
    ownerId = crypto.randomUUID();
    setSession({
      colaboradorId: ownerId,
      nome: "Operador de campo",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: ["00000000-0000-4000-8000-000000000001"],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
  });

  /*
   * Limpar é cortina, e a cortina mora aqui. O servidor também a guarda, mas a
   * tela lê o aparelho: sem este filtro, "limpar" gravava no servidor e o
   * histórico continuava na tela, com o botão parecendo morto.
   *
   * <p>Os instantes são absurdos de propósito — muito antes e muito depois de
   * qualquer mensagem — para o teste provar a regra sem depender de dois
   * relógios caírem em milissegundos diferentes.
   */
  it("esconde do histórico local o que veio antes da cortina", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000030";
    await queueMessage({
      conversaId,
      corpo: "Combinado de ontem",
      files: [],
    });

    await gravarPreferenciaDaConversa(conversaId, {
      limpoAte: "2999-01-01T00:00:00.000Z",
      pendente: true,
    });
    expect(await listLocalMessages(conversaId)).toHaveLength(0);

    // Cortina antiga não esconde nada: o que veio depois dela continua à vista.
    await gravarPreferenciaDaConversa(conversaId, {
      limpoAte: "2000-01-01T00:00:00.000Z",
    });
    expect(await listLocalMessages(conversaId)).toHaveLength(1);
  });

  it("reabrir a conversa devolve o que a cortina escondia", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000031";
    const antiga = await queueMessage({
      conversaId,
      corpo: "Registro que volta",
      files: [],
    });
    await gravarPreferenciaDaConversa(conversaId, {
      limpoAte: "2999-01-01T00:00:00.000Z",
      pendente: true,
    });
    expect(await listLocalMessages(conversaId)).toHaveLength(0);

    await gravarPreferenciaDaConversa(conversaId, { limpoAte: null });

    // A mensagem nunca foi apagada — estava atrás da cortina.
    expect((await listLocalMessages(conversaId)).map((item) => item.id))
      .toEqual([antiga.id]);
  });

  it("confirma somente a preferência que não mudou durante a requisição", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000033";
    const enviada = await gravarPreferenciaDaConversa(conversaId, {
      arquivadoEm: "2026-08-19T16:00:00.000Z",
      pendente: true,
    });

    expect(
      await confirmarPreferenciaDaConversaSincronizada(enviada),
    ).toBe(true);
    await expect((await getCortexDb()).get(
      "mensagem_preferencias",
      conversaId,
    )).resolves.toMatchObject({ pendente: false });

    const antiga = await gravarPreferenciaDaConversa(conversaId, {
      limpoAte: "2026-08-19T16:30:00.000Z",
      pendente: true,
    });
    await gravarPreferenciaDaConversa(conversaId, {
      limpoAte: null,
      pendente: true,
    });

    expect(
      await confirmarPreferenciaDaConversaSincronizada(antiga),
    ).toBe(false);
    await expect((await getCortexDb()).get(
      "mensagem_preferencias",
      conversaId,
    )).resolves.toMatchObject({ limpoAte: null, pendente: true });
  });

  it("confirma um campo sem apagar a alteração concorrente do outro", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000036";
    const arquivamentoEnviado = await gravarPreferenciaDaConversa(conversaId, {
      arquivadoEm: "2026-08-19T16:00:00.000Z",
      pendente: true,
    });
    await gravarPreferenciaDaConversa(conversaId, {
      limpoAte: "2026-08-19T16:01:00.000Z",
      pendente: true,
    });

    expect(
      await confirmarPreferenciaDaConversaSincronizada(
        arquivamentoEnviado,
        undefined,
        ["ARQUIVAMENTO"],
      ),
    ).toBe(true);
    await expect((await getCortexDb()).get(
      "mensagem_preferencias",
      conversaId,
    )).resolves.toMatchObject({
      arquivadoPendente: false,
      limpoPendente: true,
      pendente: true,
    });
  });

  it("compara a cortina por instante quando a precisão ISO varia", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000032";
    const message = (
      id: string,
      criadaNoClienteEm: string,
    ) => ({
      id,
      conversaId,
      autorId: ownerId,
      autorNome: "Operador de campo",
      corpo: id,
      status: "ATIVA" as const,
      clientMutationId: `mutation-${id}`,
      criadaNoClienteEm,
      criadaEm: criadaNoClienteEm,
      editadaEm: null,
      deletadaEm: null,
      versao: 1,
      anexos: [],
    });
    await storeServerConversations([{
      id: conversaId,
      tipo: "GRUPO",
      titulo: "Conversa com precisao variavel",
      obraId: null,
      equipeId: null,
      status: "ATIVA",
      criadaEm: "2026-08-14T11:59:00.000Z",
      atualizadaEm: "2026-08-14T12:01:00.000Z",
      versao: 1,
      participantes: [],
    }]);
    await storeServerMessages([
      message("exact-second", "2026-08-14T12:00:00Z"),
      message("fractional-second", "2026-08-14T12:00:00.500Z"),
    ]);
    await gravarPreferenciaDaConversa(conversaId, {
      limpoAte: "2026-08-14T12:00:00Z",
    });

    expect((await listLocalMessages(conversaId)).map((item) => item.id))
      .toEqual(["fractional-second"]);
  });

  it("applies another device cutoff to an already cached history", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000037";
    const conversation = {
      id: conversaId,
      tipo: "GRUPO" as const,
      titulo: "Assunto compartilhado",
      obraId: null,
      equipeId: null,
      status: "ATIVA",
      criadaEm: "2026-08-19T09:00:00.000Z",
      atualizadaEm: "2026-08-19T10:05:00.000Z",
      versao: 1,
      participantes: [],
    };
    const message = (id: string, criadaEm: string) => ({
      id,
      conversaId,
      autorId: ownerId,
      autorNome: "Colega",
      corpo: id,
      status: "ATIVA" as const,
      clientMutationId: `mutation-${id}`,
      criadaNoClienteEm: criadaEm,
      criadaEm,
      editadaEm: null,
      deletadaEm: null,
      versao: 1,
      anexos: [],
    });
    await storeServerConversations([conversation]);
    await storeServerMessages([
      message("m1", "2026-08-19T09:55:00.000Z"),
      message("m2", "2026-08-19T10:05:00.000Z"),
    ]);

    await storeServerConversations([conversation], {
      authorizedConversationIds: [conversaId],
      archivedConversationIds: [],
      historyCutoffs: [{
        conversationId: conversaId,
        limpoAte: "2026-08-19T10:00:00.000Z",
      }],
      requestStartedAt: "2026-08-19T10:10:00.000Z",
    });

    expect((await listLocalMessages(conversaId)).map((item) => item.id))
      .toEqual(["m2"]);
    await expect(searchLocalMessages("m1")).resolves.toEqual([]);
    await expect(searchLocalMessages("m2")).resolves.toHaveLength(1);
  });

  it("keeps searchable messages for an authorized conversation outside paginated metadata", async () => {
    const authorizedIds = Array.from(
      { length: 101 },
      (_value, index) => `authorized-${String(index + 1).padStart(3, "0")}`,
    );
    const targetConversationId = authorizedIds[100];
    const conversation = (id: string) => ({
      id,
      tipo: "GRUPO" as const,
      titulo: id,
      obraId: null,
      equipeId: null,
      status: "ATIVA",
      criadaEm: "2026-08-19T09:00:00.000Z",
      atualizadaEm: "2026-08-19T10:00:00.000Z",
      versao: 1,
      participantes: [],
    });
    await storeServerConversations(
      authorizedIds.slice(0, 100).map(conversation),
      {
        authorizedConversationIds: authorizedIds,
        archivedConversationIds: [],
        historyCutoffs: authorizedIds.map((conversationId) => ({
          conversationId,
          limpoAte: null,
        })),
        requestStartedAt: "2026-08-19T10:00:00.000Z",
      },
    );

    await storeServerMessages([{
      id: "message-outside-first-page",
      conversaId: targetConversationId,
      autorId: ownerId,
      autorNome: "Colega",
      corpo: "Conteudo autorizado fora da primeira pagina",
      status: "ATIVA",
      clientMutationId: "mutation-outside-first-page",
      criadaNoClienteEm: "2026-08-19T10:01:00.000Z",
      criadaEm: "2026-08-19T10:01:00.000Z",
      editadaEm: null,
      deletadaEm: null,
      versao: 1,
      anexos: [],
    }], undefined, {
      requestOrdinal: await reserveMessagingRequestOrdinal(),
    });

    await expect(
      searchLocalMessages("fora da primeira pagina"),
    ).resolves.toMatchObject([{
      id: "message-outside-first-page",
      conversaId: targetConversationId,
    }]);
  });

  it("rejects an older authorization snapshot with the same wall-clock timestamp", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000043";
    const conversation = {
      id: conversaId,
      tipo: "GRUPO" as const,
      titulo: "Snapshot antigo",
      obraId: null,
      equipeId: null,
      status: "ATIVA",
      criadaEm: "2026-08-19T09:00:00.000Z",
      atualizadaEm: "2026-08-19T10:00:00.000Z",
      versao: 1,
      participantes: [],
    };
    const [olderOrdinal, newerOrdinal] = await Promise.all([
      reserveMessagingRequestOrdinal(),
      reserveMessagingRequestOrdinal(),
    ]).then((ordinals) => ordinals.sort((left, right) => left - right));

    await storeServerConversations([], {
      authorizedConversationIds: [],
      archivedConversationIds: [],
      historyCutoffs: [],
      requestStartedAt: "2026-08-19T10:00:00.000Z",
      authorizationSnapshotOrdinal: newerOrdinal,
    });
    await storeServerConversations([conversation], {
      authorizedConversationIds: [conversaId],
      archivedConversationIds: [],
      historyCutoffs: [{ conversationId: conversaId, limpoAte: null }],
      requestStartedAt: "2026-08-19T10:00:00.000Z",
      authorizationSnapshotOrdinal: olderOrdinal,
    });

    const database = await getCortexDb();
    await expect(database.get("mensagem_conversas", conversaId))
      .resolves.toBeUndefined();
    await expect(database.get("mensagem_autorizacoes", conversaId))
      .resolves.toBeUndefined();
  });

  it("orders authorization snapshots even when the wall clock moves backwards", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000044";
    const olderOrdinal = await reserveMessagingRequestOrdinal();
    const newerOrdinal = await reserveMessagingRequestOrdinal();

    await storeServerConversations([], {
      authorizedConversationIds: [],
      archivedConversationIds: [],
      historyCutoffs: [],
      requestStartedAt: "2000-01-01T00:00:00.000Z",
      authorizationSnapshotOrdinal: newerOrdinal,
    });
    await storeServerConversations([{
      id: conversaId,
      tipo: "GRUPO",
      titulo: "Relogio regressivo",
      obraId: null,
      equipeId: null,
      status: "ATIVA",
      criadaEm: "2026-08-19T09:00:00.000Z",
      atualizadaEm: "2026-08-19T10:00:00.000Z",
      versao: 1,
      participantes: [],
    }], {
      authorizedConversationIds: [conversaId],
      archivedConversationIds: [],
      historyCutoffs: [{ conversationId: conversaId, limpoAte: null }],
      requestStartedAt: "2999-01-01T00:00:00.000Z",
      authorizationSnapshotOrdinal: olderOrdinal,
    });

    await expect((await getCortexDb()).get(
      "mensagem_conversas",
      conversaId,
    )).resolves.toBeUndefined();
  });

  it("rejects a message response started before access was revoked", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000041";
    const conversation = {
      id: conversaId,
      tipo: "GRUPO" as const,
      titulo: "Acesso temporario",
      obraId: null,
      equipeId: null,
      status: "ATIVA",
      criadaEm: "2026-08-19T09:00:00.000Z",
      atualizadaEm: "2026-08-19T10:00:00.000Z",
      versao: 1,
      participantes: [],
    };
    await storeServerConversations([conversation], {
      authorizedConversationIds: [conversaId],
      archivedConversationIds: [],
      historyCutoffs: [{ conversationId: conversaId, limpoAte: null }],
      requestStartedAt: "2026-08-19T10:00:00.000Z",
    });
    const requestOrdinal = await reserveMessagingRequestOrdinal();
    await storeServerConversations([], {
      authorizedConversationIds: [],
      archivedConversationIds: [],
      historyCutoffs: [],
      requestStartedAt: "2026-08-19T10:05:00.000Z",
    });

    await storeServerMessages([{
      id: "late-remote-message",
      conversaId,
      autorId: ownerId,
      autorNome: "Colega",
      corpo: "Resposta iniciada antes da revogacao",
      status: "ATIVA",
      clientMutationId: "late-remote-mutation",
      criadaNoClienteEm: "2026-08-19T09:59:00.000Z",
      criadaEm: "2026-08-19T09:59:00.000Z",
      editadaEm: null,
      deletadaEm: null,
      versao: 1,
      anexos: [{
        id: "late-remote-attachment",
        objetoId: "late-object",
        nome: "late.txt",
        mediaType: "text/plain",
        tamanhoBytes: 4,
        sha256: "late-sha",
        ordem: 0,
      }],
    }], undefined, {
      requestOrdinal,
    });

    const database = await getCortexDb();
    await expect(database.get("mensagens", "late-remote-message"))
      .resolves.toBeUndefined();
    await expect(database.get("mensagem_anexos", "late-remote-attachment"))
      .resolves.toBeUndefined();
  });

  it("prunes unauthorized orphaned remote content while quarantining local work", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000042";
    const conversation = {
      id: conversaId,
      tipo: "GRUPO" as const,
      titulo: "Metadado paginado",
      obraId: null,
      equipeId: null,
      status: "ATIVA",
      criadaEm: "2026-08-19T09:00:00.000Z",
      atualizadaEm: "2026-08-19T10:00:00.000Z",
      versao: 1,
      participantes: [],
    };
    await storeServerConversations([conversation], {
      authorizedConversationIds: [conversaId],
      archivedConversationIds: [],
      historyCutoffs: [{ conversationId: conversaId, limpoAte: null }],
      requestStartedAt: "2026-08-19T10:00:00.000Z",
    });
    await storeServerMessages([{
      id: "orphaned-remote-message",
      conversaId,
      autorId: ownerId,
      autorNome: "Colega",
      corpo: "Copia remota orfa",
      status: "ATIVA",
      clientMutationId: "orphaned-remote-mutation",
      criadaNoClienteEm: "2026-08-19T10:01:00.000Z",
      criadaEm: "2026-08-19T10:01:00.000Z",
      editadaEm: null,
      deletadaEm: null,
      versao: 1,
      anexos: [{
        id: "orphaned-remote-attachment",
        objetoId: "orphaned-object",
        nome: "remote.txt",
        mediaType: "text/plain",
        tamanhoBytes: 6,
        sha256: "remote-sha",
        ordem: 0,
      }],
    }], undefined, {
      requestOrdinal: await reserveMessagingRequestOrdinal(),
    });
    const local = await queueMessage({
      conversaId,
      corpo: "Trabalho local preservado",
      files: [new File(["local"], "local.txt", { type: "text/plain" })],
    });
    const database = await getCortexDb();
    await database.delete("mensagem_conversas", conversaId);

    await storeServerConversations([], {
      authorizedConversationIds: [],
      archivedConversationIds: [],
      historyCutoffs: [],
      requestStartedAt: "2026-08-19T10:05:00.000Z",
    });

    await expect(database.get("mensagens", "orphaned-remote-message"))
      .resolves.toBeUndefined();
    await expect(database.get("mensagem_anexos", "orphaned-remote-attachment"))
      .resolves.toBeUndefined();
    await expect(database.get("mensagens", local.id)).resolves.toMatchObject({
      corpo: "Trabalho local preservado",
      syncStatus: "FALHOU",
    });
    await expect(database.get("mensagem_anexos", local.anexos[0].id))
      .resolves.toMatchObject({
        arquivo: expect.any(Blob),
        syncStatus: "FALHOU",
      });
    expect(
      (await database.getAll("outbox_mutations"))
        .filter((mutation) => mutation.payload.conversaId === conversaId),
    ).toEqual(expect.arrayContaining([
      expect.objectContaining({
        status: "REJECTED",
        lastSafeCode: "MESSAGING_ACCESS_REVOKED_REQUIRES_REVIEW",
      }),
    ]));
  });

  it("does not overwrite a newer pending local cleanup with the server cutoff", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000038";
    const conversation = {
      id: conversaId,
      tipo: "GRUPO" as const,
      titulo: "Assunto compartilhado",
      obraId: null,
      equipeId: null,
      status: "ATIVA",
      criadaEm: "2026-08-19T09:00:00.000Z",
      atualizadaEm: "2026-08-19T10:05:00.000Z",
      versao: 1,
      participantes: [],
    };
    await storeServerConversations([conversation]);
    await gravarPreferenciaDaConversa(conversaId, {
      limpoAte: "2026-08-19T10:08:00.000Z",
      pendente: true,
    });

    await storeServerConversations([conversation], {
      authorizedConversationIds: [conversaId],
      archivedConversationIds: [],
      historyCutoffs: [{
        conversationId: conversaId,
        limpoAte: "2026-08-19T10:00:00.000Z",
      }],
      requestStartedAt: "2026-08-19T10:10:00.000Z",
    });

    await expect((await getCortexDb()).get(
      "mensagem_preferencias",
      conversaId,
    )).resolves.toMatchObject({
      limpoAte: "2026-08-19T10:08:00.000Z",
      limpoPendente: true,
      pendente: true,
    });
  });

  it("uses an authoritative null cutoff to fence an older response", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000040";
    const conversation = {
      id: conversaId,
      tipo: "GRUPO" as const,
      titulo: "Assunto reaberto em outro aparelho",
      obraId: null,
      equipeId: null,
      status: "ATIVA",
      criadaEm: "2026-08-19T09:00:00.000Z",
      atualizadaEm: "2026-08-19T10:05:00.000Z",
      versao: 1,
      participantes: [],
    };

    await storeServerConversations([conversation], {
      authorizedConversationIds: [conversaId],
      archivedConversationIds: [],
      historyCutoffs: [{ conversationId: conversaId, limpoAte: null }],
      requestStartedAt: "2026-08-19T10:20:00.000Z",
    });
    await storeServerConversations([conversation], {
      authorizedConversationIds: [conversaId],
      archivedConversationIds: [],
      historyCutoffs: [{
        conversationId: conversaId,
        limpoAte: "2026-08-19T10:00:00.000Z",
      }],
      requestStartedAt: "2026-08-19T10:10:00.000Z",
    });

    await expect((await getCortexDb()).get(
      "mensagem_preferencias",
      conversaId,
    )).resolves.toMatchObject({
      limpoAte: null,
      limpoAtualizadoEm: "2026-08-19T10:20:00.000Z",
    });
  });

  it("ordena conversas pela época quando a precisão ISO varia", async () => {
    const conversation = (
      id: string,
      atualizadaEm: string,
    ) => ({
      id,
      tipo: "OBRA" as const,
      titulo: id,
      obraId: "00000000-0000-4000-8000-000000000001",
      equipeId: null,
      status: "ATIVA",
      criadaEm: atualizadaEm,
      atualizadaEm,
      versao: 1,
      participantes: [],
    });
    await storeServerConversations([
      conversation("exact-second", "2026-08-14T12:00:00Z"),
      conversation("fractional-second", "2026-08-14T12:00:00.500Z"),
    ]);

    expect((await listLocalConversations()).map((item) => item.id))
      .toEqual(["fractional-second", "exact-second"]);
  });

  it("prunes only ids missing from the complete authorization snapshot", async () => {
    const conversation = (id: string) => ({
      id,
      tipo: "GRUPO" as const,
      titulo: id,
      obraId: null,
      equipeId: null,
      status: "ATIVA",
      criadaEm: "2026-08-19T12:00:00.000Z",
      atualizadaEm: "2026-08-19T12:00:00.000Z",
      versao: 1,
      participantes: [],
    });
    await storeServerConversations([
      conversation("active-first-page"),
      conversation("archived-first-page"),
      conversation("authorized-outside-page"),
      conversation("revoked"),
    ]);

    await storeServerConversations(
      [
        conversation("active-first-page"),
        conversation("archived-first-page"),
      ],
      {
        authorizedConversationIds: [
          "active-first-page",
          "archived-first-page",
          "authorized-outside-page",
        ],
        archivedConversationIds: ["archived-first-page"],
        requestStartedAt: "2026-08-19T13:00:00.000Z",
      },
    );

    expect((await listLocalConversations()).map((item) => item.id).sort())
      .toEqual([
        "active-first-page",
        "archived-first-page",
        "authorized-outside-page",
      ]);
    await expect((await getCortexDb()).get(
      "mensagem_preferencias",
      "archived-first-page",
    )).resolves.toMatchObject({
      conversaId: "archived-first-page",
      arquivadoEm: "2026-08-19T13:00:00.000Z",
      pendente: false,
    });
  });

  it("quarantines unsent message work when conversation access is revoked", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000039";
    const conversation = {
      id: conversaId,
      tipo: "GRUPO" as const,
      titulo: "Acesso posteriormente revogado",
      obraId: null,
      equipeId: null,
      status: "ATIVA",
      criadaEm: "2026-08-19T09:00:00.000Z",
      atualizadaEm: "2026-08-19T10:05:00.000Z",
      versao: 1,
      participantes: [],
    };
    await storeServerConversations([conversation]);
    await storeServerMessages([{
      id: "remote-m1",
      conversaId,
      autorId: ownerId,
      autorNome: "Colega",
      corpo: "Conteudo remoto",
      status: "ATIVA",
      clientMutationId: "remote-mutation",
      criadaNoClienteEm: "2026-08-19T09:55:00.000Z",
      criadaEm: "2026-08-19T09:55:00.000Z",
      editadaEm: null,
      deletadaEm: null,
      versao: 1,
      anexos: [],
    }]);
    const local = await queueMessage({
      conversaId,
      corpo: "Unica copia local",
      files: [new File(["evidencia"], "evidencia.txt", {
        type: "text/plain",
      })],
    });

    await storeServerConversations([], {
      authorizedConversationIds: [],
      archivedConversationIds: [],
      historyCutoffs: [],
      requestStartedAt: "2026-08-19T10:10:00.000Z",
    });

    const database = await getCortexDb();
    await expect(database.get("mensagem_conversas", conversaId))
      .resolves.toBeUndefined();
    await expect(database.get("mensagens", "remote-m1"))
      .resolves.toBeUndefined();
    await expect(database.get("mensagens", local.id))
      .resolves.toMatchObject({
        corpo: "Unica copia local",
        syncStatus: "FALHOU",
      });
    await expect(database.get("mensagem_anexos", local.anexos[0].id))
      .resolves.toMatchObject({
        arquivo: expect.any(Blob),
        syncStatus: "FALHOU",
      });
    const quarantined = (await database.getAll("outbox_mutations")).filter(
      (mutation) => mutation.payload.conversaId === conversaId,
    );
    expect(quarantined).toHaveLength(2);
    expect(quarantined).toEqual(expect.arrayContaining([
      expect.objectContaining({
        status: "REJECTED",
        lastSafeCode: "MESSAGING_ACCESS_REVOKED_REQUIRES_REVIEW",
        conflito: expect.objectContaining({
          tipo: "MENSAGEM_ACESSO_REVOGADO",
        }),
      }),
    ]));
    expect(quarantined.every((mutation) => mutation.status === "REJECTED"))
      .toBe(true);
    await expect(listReadyPendingOutboxMutations(100)).resolves.toEqual([]);
    await expect(listLocalMessages(conversaId)).resolves.toEqual([]);
    await expect(searchLocalMessages("Unica copia local"))
      .resolves.toEqual([]);
  });

  it("records independent ordering metadata for archive and history gestures", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000034";
    await gravarPreferenciaDaConversa(conversaId, {
      arquivadoEm: "2026-08-19T14:00:00.000Z",
      pendente: true,
    });
    const preference = await gravarPreferenciaDaConversa(conversaId, {
      limpoAte: "2026-08-19T14:05:00.000Z",
      pendente: true,
    });

    expect(preference).toMatchObject({
      arquivadoAtualizadoEm: expect.any(String),
      limpoAtualizadoEm: expect.any(String),
    });
  });

  it("does not overwrite a newer confirmed archive gesture with a stale list", async () => {
    const conversaId = "00000000-0000-4000-8000-000000000035";
    const conversation = {
      id: conversaId,
      tipo: "GRUPO" as const,
      titulo: "Frente B",
      obraId: null,
      equipeId: null,
      status: "ATIVA",
      criadaEm: "2026-08-19T12:00:00.000Z",
      atualizadaEm: "2026-08-19T12:00:00.000Z",
      versao: 1,
      participantes: [],
    };
    await storeServerConversations([conversation]);
    const archived = await gravarPreferenciaDaConversa(conversaId, {
      arquivadoEm: "2026-08-19T14:00:00.000Z",
      pendente: true,
    });
    await confirmarPreferenciaDaConversaSincronizada(archived);

    await storeServerConversations([conversation], {
      authorizedConversationIds: [conversaId],
      archivedConversationIds: [],
      requestStartedAt: "2000-01-01T00:00:00.000Z",
    });

    await expect((await getCortexDb()).get(
      "mensagem_preferencias",
      conversaId,
    )).resolves.toMatchObject({
      arquivadoEm: "2026-08-19T14:00:00.000Z",
      pendente: false,
    });
  });

  it("persists the attachment Blob and dependency graph for a later reload", async () => {
    const queued = await queueMessage({
      conversaId: "00000000-0000-4000-8000-000000000010",
      corpo: "Anexo salvo offline",
      files: [
        new File(["conteudo persistido"], "registro.txt", {
          type: "text/plain",
        }),
      ],
    });

    const databaseName = await databaseNameForScope(
      ownerId,
      "BETA:00000000-0000-4000-8000-000000000001",
    );
    const reopened = await openDB(databaseName, CORTEX_DATABASE_VERSION);
    const storedAttachment = await reopened.get(
      "mensagem_anexos",
      queued.anexos[0].id,
    );
    const messageMutation = await reopened.get(
      "outbox_mutations",
      queued.clientMutationId,
    );

    expect(storedAttachment.arquivo).toBeInstanceOf(Blob);
    expect(await storedAttachment.arquivo.text()).toBe("conteudo persistido");
    expect(storedAttachment.sha256).toMatch(/^[a-f0-9]{64}$/);
    expect(messageMutation.dependsOnMutationIds).toEqual([
      storedAttachment.uploadMutationId,
    ]);
    reopened.close();
  });

  it("rolls back authorized conversation hydration on same-scope session rotation", async () => {
    const database = await getCortexDb();
    const guard = captureOnlineSyncSession();
    const originalSession = getSession()!;
    const originalPut = IDBObjectStore.prototype.put;
    let rotated = false;
    const putSpy = vi
      .spyOn(IDBObjectStore.prototype, "put")
      .mockImplementation(function (
        this: IDBObjectStore,
        value: unknown,
        key?: IDBValidKey,
      ) {
        if (!rotated) {
          rotated = true;
          setSession({
            ...originalSession,
            expiraEm: new Date(
              Date.parse(originalSession.expiraEm) + 60_000,
            ).toISOString(),
          });
        }
        return key === undefined
          ? originalPut.call(this, value)
          : originalPut.call(this, value, key);
      });

    try {
      await expect(
        storeServerConversations(
          [{
            id: "00000000-0000-4000-8000-000000000011",
            tipo: "OBRA",
            titulo: "Equipe de campo",
            obraId: "00000000-0000-4000-8000-000000000001",
            equipeId: null,
            status: "ATIVA",
            criadaEm: "2026-07-22T12:00:00.000Z",
            atualizadaEm: "2026-07-22T12:00:00.000Z",
            versao: 1,
            participantes: [],
          }],
          {
            authorizedConversationIds: [
              "00000000-0000-4000-8000-000000000011",
            ],
          },
          guard,
        ),
      ).rejects.toBeDefined();
    } finally {
      putSpy.mockRestore();
      setSession(originalSession);
    }

    expect(
      await database.get(
        "mensagem_conversas",
        "00000000-0000-4000-8000-000000000011",
      ),
    ).toBeUndefined();
  });

  it("rolls back message history hydration on same-scope session rotation", async () => {
    const database = await getCortexDb();
    await storeServerConversations([{
      id: "00000000-0000-4000-8000-000000000011",
      tipo: "OBRA",
      titulo: "Equipe de campo",
      obraId: "00000000-0000-4000-8000-000000000001",
      equipeId: null,
      status: "ATIVA",
      criadaEm: "2026-07-22T12:00:00.000Z",
      atualizadaEm: "2026-07-22T12:00:00.000Z",
      versao: 1,
      participantes: [],
    }]);
    const guard = captureOnlineSyncSession();
    const originalSession = getSession()!;
    const originalPut = IDBObjectStore.prototype.put;
    let rotated = false;
    const putSpy = vi
      .spyOn(IDBObjectStore.prototype, "put")
      .mockImplementation(function (
        this: IDBObjectStore,
        value: unknown,
        key?: IDBValidKey,
      ) {
        if (!rotated) {
          rotated = true;
          setSession({
            ...originalSession,
            expiraEm: new Date(
              Date.parse(originalSession.expiraEm) + 60_000,
            ).toISOString(),
          });
        }
        return key === undefined
          ? originalPut.call(this, value)
          : originalPut.call(this, value, key);
      });

    try {
      await expect(
        storeServerMessages(
          [{
            id: "00000000-0000-4000-8000-000000000012",
            conversaId: "00000000-0000-4000-8000-000000000011",
            autorId: ownerId,
            autorNome: "Operador de campo",
            corpo: "Mensagem remota",
            status: "ATIVA",
            clientMutationId: "00000000-0000-4000-8000-000000000013",
            criadaNoClienteEm: "2026-07-22T12:00:00.000Z",
            criadaEm: "2026-07-22T12:00:00.000Z",
            editadaEm: null,
            deletadaEm: null,
            versao: 1,
            anexos: [],
          }],
          guard,
        ),
      ).rejects.toBeDefined();
    } finally {
      putSpy.mockRestore();
      setSession(originalSession);
    }

    expect(
      await database.get(
        "mensagens",
        "00000000-0000-4000-8000-000000000012",
      ),
    ).toBeUndefined();
  });
});
