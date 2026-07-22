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
