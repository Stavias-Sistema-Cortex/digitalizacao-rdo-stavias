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
import {
  gravarPreferenciaDaConversa,
  listLocalConversations,
  listLocalMessages,
  queueMessage,
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

  it("rolls back authoritative conversation hydration on same-scope session rotation", async () => {
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
          { authoritative: true },
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
