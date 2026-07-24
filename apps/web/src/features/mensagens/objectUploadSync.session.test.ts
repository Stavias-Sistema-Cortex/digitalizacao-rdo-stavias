import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, getSession, setSession } from "../auth/authSession";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import { captureOnlineSyncSession } from "../../lib/sync/syncSession";

const mocks = vi.hoisted(() => ({ apiFetch: vi.fn(), body: vi.fn() }));
vi.mock("../../lib/api/apiClient", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/api/apiClient")>()),
  apiFetch: mocks.apiFetch,
  readResponseBody: mocks.body,
}));

import { processObjectUploads } from "./objectUploadSync";

const OBRA_ID = "00000000-0000-4000-8000-000000000401";
const USER_A = "00000000-0000-4000-8000-000000000402";
const USER_B = "00000000-0000-4000-8000-000000000403";
const UPLOAD_ID = "00000000-0000-4000-8000-000000000404";
const ATTACHMENT_ID = "00000000-0000-4000-8000-000000000405";
const MESSAGE_ID = "00000000-0000-4000-8000-000000000406";
const CONVERSATION_ID = "00000000-0000-4000-8000-000000000407";
let databaseA = "";
let databaseB = "";

function profile(userId: string) {
  return {
    colaboradorId: userId,
    nome: userId === USER_A ? "A" : "B",
    papelAcesso: "BETA" as const,
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  setSession(profile(USER_A));
  databaseA = await databaseNameForScope(USER_A, `BETA:${OBRA_ID}`);
  databaseB = await databaseNameForScope(USER_B, `BETA:${OBRA_ID}`);
  const database = await getCortexDb();
  await database.put("mensagem_conversas", {
    id: CONVERSATION_ID,
    tipo: "OBRA",
    titulo: "Obra",
    obraId: OBRA_ID,
    equipeId: null,
    status: "ATIVA",
    participantes: [],
    criadaEm: "2026-07-22T12:00:00.000Z",
    atualizadaEm: "2026-07-22T12:00:00.000Z",
    versaoEntidade: 1,
  });
  await database.put("mensagens", {
    id: MESSAGE_ID,
    conversaId: CONVERSATION_ID,
    autorId: USER_A,
    autorNome: "A",
    corpo: "foto",
    status: "ATIVA",
    clientMutationId: MESSAGE_ID,
    criadaNoClienteEm: "2026-07-22T12:00:00.000Z",
    criadaEm: null,
    editadaEm: null,
    deletadaEm: null,
    versaoEntidade: null,
    syncStatus: "NA_FILA",
    ultimoErro: null,
    updatedAt: "2026-07-22T12:00:00.000Z",
  });
  await database.put("mensagem_anexos", {
    id: ATTACHMENT_ID,
    mensagemId: MESSAGE_ID,
    conversaId: CONVERSATION_ID,
    objetoId: null,
    uploadMutationId: UPLOAD_ID,
    nome: "foto.jpg",
    mediaType: "image/jpeg",
    tamanhoBytes: 3,
    sha256: "a".repeat(64),
    ordem: 0,
    arquivo: new Blob(["abc"], { type: "image/jpeg" }),
    syncStatus: "NA_FILA",
    ultimoErro: null,
    createdAt: "2026-07-22T12:00:00.000Z",
    updatedAt: "2026-07-22T12:00:00.000Z",
  });
  await database.put("outbox_mutations", {
    clientMutationId: UPLOAD_ID,
    entidadeTipo: "MENSAGEM_ANEXO",
    entidadeId: ATTACHMENT_ID,
    operacao: "ADICIONAR_MENSAGEM_ANEXO",
    baseVersao: null,
    payload: {},
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: "2026-07-22T12:00:00.000Z",
    updatedAt: "2026-07-22T12:00:00.000Z",
    transport: "OBJECT_UPLOAD",
  });
});

afterEach(async () => {
  await closeCortexDb();
  await deleteDB(databaseA);
  await deleteDB(databaseB);
  clearSession();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("object upload session guard", () => {
  it("persists a transient upload retry per row instead of requiring a manual action", async () => {
    const { ApiTransportError } = await import("../../lib/api/apiClient");
    mocks.apiFetch.mockRejectedValueOnce(
      new ApiTransportError("rede indisponível", "CONNECTION"),
    );

    await expect(processObjectUploads()).resolves.toMatchObject({ errors: 1 });

    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", UPLOAD_ID)).toMatchObject({
      status: "PENDING",
      retryAttempt: 1,
      lastSafeCode: "NETWORK_UNAVAILABLE",
      nextAttemptAt: expect.any(String),
    });
  });

  it.each([
    { status: 403, code: "ACCESS_DENIED" },
    { status: 422, code: "UPLOAD_INVALID" },
  ])(
    "keeps upload HTTP $status terminal until user or session action",
    async ({ status, code }) => {
      mocks.apiFetch.mockResolvedValueOnce(
        new Response("{}", { status }),
      );
      mocks.body.mockResolvedValueOnce({ code });

      await expect(processObjectUploads()).resolves.toMatchObject({
        errors: 1,
      });

      const database = await getCortexDb();
      expect(await database.get("outbox_mutations", UPLOAD_ID)).toMatchObject({
        status: "REJECTED",
        retryAttempt: 0,
        nextAttemptAt: null,
        lastSafeCode: code,
      });
      expect(await database.get("mensagem_anexos", ATTACHMENT_ID)).toMatchObject({
        syncStatus: "FALHOU",
      });
    },
  );

  it.each([429, 503])(
    "schedules upload HTTP %i as transient",
    async (status) => {
      mocks.apiFetch.mockResolvedValueOnce(
        new Response("{}", { status }),
      );
      mocks.body.mockResolvedValueOnce(null);

      await expect(processObjectUploads()).resolves.toMatchObject({
        errors: 1,
      });

      const database = await getCortexDb();
      expect(await database.get("outbox_mutations", UPLOAD_ID)).toMatchObject({
        status: "PENDING",
        retryAttempt: 1,
        nextAttemptAt: expect.any(String),
        lastSafeCode: `HTTP_${status}_TRANSIENT`,
      });
    },
  );

  it("fails closed after fetch and never applies into the replacement session", async () => {
    const guard = captureOnlineSyncSession();
    mocks.apiFetch.mockImplementationOnce(async () => {
      setSession(profile(USER_B));
      return new Response("{}", { status: 200 });
    });
    mocks.body.mockResolvedValue({
      id: "00000000-0000-4000-8000-000000000408",
      obraId: OBRA_ID,
      nomeOriginal: "foto.jpg",
      mediaType: "image/jpeg",
      tamanhoBytes: 3,
      sha256: "a".repeat(64),
      status: "DISPONIVEL",
      criadoEm: "2026-07-22T12:00:01.000Z",
      deduplicado: false,
    });

    await expect(processObjectUploads(20, guard)).rejects.toThrow(
      "A sessão mudou durante a sincronização.",
    );

    setSession(profile(USER_A));
    const originalDatabase = await getCortexDb();
    expect(
      await originalDatabase.get("outbox_mutations", UPLOAD_ID),
    ).toMatchObject({ status: "SYNCING" });
    setSession(profile(USER_B));
    const replacementDatabase = await getCortexDb();
    expect(
      await replacementDatabase.get("outbox_mutations", UPLOAD_ID),
    ).toBeUndefined();
  });

  it("rolls back upload state when the same-scope session rotates mid-transaction", async () => {
    const guard = captureOnlineSyncSession();
    const database = await getCortexDb();
    const originalSession = getSession()!;
    const databaseWithTransaction = database as unknown as {
      transaction: (...args: unknown[]) => unknown;
    };
    const originalTransaction =
      databaseWithTransaction.transaction.bind(database);
    let startedTransaction: { done: Promise<unknown> } | null = null;
    const transactionSpy = vi
      .spyOn(databaseWithTransaction, "transaction")
      .mockImplementation(
      (...args: unknown[]) => {
        const transaction = originalTransaction(...args);
        startedTransaction = transaction as { done: Promise<unknown> };
        queueMicrotask(() => {
          setSession({
            ...originalSession,
            expiraEm: new Date(
              Date.parse(originalSession.expiraEm) + 60_000,
            ).toISOString(),
          });
        });
        return transaction;
      },
      );

    await expect(processObjectUploads(20, guard)).rejects.toBeDefined();
    await startedTransaction?.done.catch(() => undefined);

    transactionSpy.mockRestore();
    setSession(originalSession);
    expect(await database.get("outbox_mutations", UPLOAD_ID)).toMatchObject({
      status: "PENDING",
    });
    expect(await database.get("mensagem_anexos", ATTACHMENT_ID)).toMatchObject({
      syncStatus: "NA_FILA",
    });
  });
});
