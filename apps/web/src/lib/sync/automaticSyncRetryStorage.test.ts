import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import {
  closeCortexDb,
  getCortexDb,
} from "../db/cortexDb";
import type { OutboxMutationRecord } from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import {
  clearAutomaticSyncRetryMetadata,
  loadAutomaticSyncRetryMetadata,
  persistAutomaticSyncRetryMetadata,
  returnMutationToPending,
} from "./syncStorage";

const OBRA_ID = "00000000-0000-4000-8000-000000000002";
const SCOPE_MATERIAL = `BETA:${OBRA_ID}`;

function pendingMutation(
  clientMutationId = "retry-storage-1",
  status: OutboxMutationRecord["status"] = "PENDING",
): OutboxMutationRecord {
  return {
    clientMutationId,
    entidadeTipo: "RDO",
    entidadeId: "rdo-1",
    operacao: "CRIAR_RDO",
    baseVersao: null,
    payload: { id: "rdo-1", preserved: true },
    status,
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: "Falha transitória",
    conflito: null,
    criadaNoClienteEm: "2026-07-17T12:00:00.000Z",
    updatedAt: "2026-07-17T12:00:00.000Z",
    nextAttemptAt: null,
  };
}

describe("automatic sync retry IndexedDB persistence", () => {
  let databaseName = "";

  beforeEach(async () => {
    vi.stubGlobal("BroadcastChannel", undefined);
    const ownerId = crypto.randomUUID();
    setSession({
      colaboradorId: ownerId,
      nome: "Operador de campo",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: [OBRA_ID],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
    databaseName = await databaseNameForScope(
      ownerId,
      SCOPE_MATERIAL,
    );
  });

  afterEach(async () => {
    await closeCortexDb();
    if (databaseName) {
      await deleteDB(databaseName);
    }
    clearSession();
    vi.unstubAllGlobals();
  });

  it("persists, reloads and clears retry metadata across database reopens", async () => {
    const database = await getCortexDb();
    await database.put(
      "outbox_mutations",
      pendingMutation(),
    );

    await persistAutomaticSyncRetryMetadata({
      attempt: 3,
      nextAttemptAt: "2026-07-17T12:05:00.000Z",
    });
    await closeCortexDb();

    await expect(
      loadAutomaticSyncRetryMetadata(),
    ).resolves.toEqual({
      attempt: 3,
      nextAttemptAt: "2026-07-17T12:05:00.000Z",
    });
    expect(
      await (await getCortexDb()).get(
        "outbox_mutations",
        "retry-storage-1",
      ),
    ).toMatchObject({
      status: "PENDING",
      tentativas: 3,
      nextAttemptAt: "2026-07-17T12:05:00.000Z",
      payload: { id: "rdo-1", preserved: true },
    });

    await clearAutomaticSyncRetryMetadata();
    await closeCortexDb();

    const reopened = await getCortexDb();
    expect(
      await reopened.get(
        "outbox_mutations",
        "retry-storage-1",
      ),
    ).toMatchObject({
      status: "PENDING",
      tentativas: 0,
      ultimaTentativaEm: null,
      nextAttemptAt: null,
      payload: { id: "rdo-1", preserved: true },
    });
    await expect(
      loadAutomaticSyncRetryMetadata(),
    ).resolves.toEqual({
      attempt: 0,
      nextAttemptAt: null,
    });
  });

  it("returns only SYNCING rows to PENDING and preserves every terminal status", async () => {
    const database = await getCortexDb();
    const terminalMutations = [
      pendingMutation("terminal-synced", "SYNCED"),
      pendingMutation("terminal-conflict", "CONFLICT"),
      pendingMutation("terminal-error", "ERROR"),
    ];
    const inProgress = pendingMutation("retry-in-progress", "SYNCING");

    await Promise.all(
      [...terminalMutations, inProgress].map((mutation) =>
        database.put("outbox_mutations", mutation),
      ),
    );

    for (const mutation of terminalMutations) {
      await returnMutationToPending(
        mutation.clientMutationId,
        "Falha posterior no lote",
      );
    }
    await returnMutationToPending(
      inProgress.clientMutationId,
      "Falha posterior no lote",
    );

    for (const mutation of terminalMutations) {
      await expect(
        database.get(
          "outbox_mutations",
          mutation.clientMutationId,
        ),
      ).resolves.toMatchObject({
        status: mutation.status,
        ultimoErro: mutation.ultimoErro,
      });
    }
    await expect(
      database.get(
        "outbox_mutations",
        inProgress.clientMutationId,
      ),
    ).resolves.toMatchObject({
      status: "PENDING",
      ultimoErro: "Falha posterior no lote",
    });
  });
});
