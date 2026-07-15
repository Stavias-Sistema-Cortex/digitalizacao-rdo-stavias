import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  closeCortexDb,
  CORTEX_DATABASE_NAME,
  getCortexDb,
} from "./cortexDb";
import {
  bindLocalDataToUser,
  clearUserScopedLocalStorage,
} from "./localDataScope";

async function resetDatabase(): Promise<void> {
  await closeCortexDb();
  await deleteDB(CORTEX_DATABASE_NAME);
}

beforeEach(resetDatabase);
afterEach(resetDatabase);

describe("bindLocalDataToUser", () => {
  it("remove o histórico auxiliar da StavIA ao encerrar ou trocar a sessão", () => {
    const removed: string[] = [];

    clearUserScopedLocalStorage({
      removeItem(key: string) {
        removed.push(key);
      },
    });

    expect(removed).toEqual([
      "cortex:stavia:chat:operacional",
      "cortex:stavia:last-context",
    ]);
  });

  it("preserva o cache da mesma identidade", async () => {
    await bindLocalDataToUser("user-1");
    const database = await getCortexDb();
    await database.put("obras", {
      id: "obra-1",
      codigoContrato: "001",
      nome: "Obra Norte",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "ATIVA",
      observacoes: null,
      latitude: null,
      longitude: null,
      valorContratual: null,
      updatedAt: "2026-07-15T12:00:00Z",
    });

    expect(await bindLocalDataToUser("user-1")).toBe(false);
    expect(await database.count("obras")).toBe(1);
  });

  it("remove dados privados e cursores antes de vincular outro usuário", async () => {
    await bindLocalDataToUser("user-1");
    const database = await getCortexDb();
    await database.put("messages", {
      id: "message-1",
      conversaId: "conversation-1",
      remetenteId: "user-1",
      remetenteNome: "Ana",
      clientMessageId: "client-1",
      texto: "Dado privado",
      estado: "ENVIADA",
      enviadaClienteEm: "2026-07-15T12:00:00Z",
      criadaServidorEm: "2026-07-15T12:00:00Z",
      atualizadoEm: "2026-07-15T12:00:00Z",
      versaoEntidade: 1,
      recibos: [],
      syncStatus: "SENT",
      ultimoErro: null,
    });
    await database.put("sync_state", {
      key: "default",
      deviceId: "device-1",
      usuarioId: "user-1",
      lastPulledCommitSeq: 99,
      lastAckedCommitSeq: 98,
      isSyncing: false,
      lastSyncStartedAt: null,
      lastSyncCompletedAt: "2026-07-15T12:00:00Z",
      lastSyncError: null,
    });

    expect(await bindLocalDataToUser("user-2")).toBe(true);
    expect(await database.count("messages")).toBe(0);
    expect(await database.get("sync_state", "default")).toEqual({
      key: "default",
      deviceId: null,
      usuarioId: "user-2",
      lastPulledCommitSeq: 0,
      lastAckedCommitSeq: 0,
      isSyncing: false,
      lastSyncStartedAt: null,
      lastSyncCompletedAt: null,
      lastSyncError: null,
    });
  });
});
