import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import { databaseNameForScope } from "../db/localDataNamespace";
import { updateSyncState } from "../db/syncStateRepository";
import {
  descartarEdicaoEmConflito,
  descartarMutacoesMortas,
} from "./syncStorage";

const COLABORADOR_ID = "00000000-0000-4000-8000-0000000005b1";
const OBRA_ID = "00000000-0000-4000-8000-0000000005b2";
const EQUIPE_ID = "00000000-0000-4000-8000-0000000005b3";
const DEVICE_ID = "00000000-0000-4000-8000-0000000005b4";

let databaseName = "";

async function semearEquipeTravada() {
  const database = await getCortexDb();
  await database.put("teams", {
    id: EQUIPE_ID,
    obraPrincipalId: OBRA_ID,
    obraNome: "Quarta intervenção",
    nome: "FR Conservação",
    descricao: "",
    status: "ARQUIVADA",
    inicioValidadeEm: "2026-08-01",
    fimValidadeEm: "2026-08-05",
    versaoEntidade: 6,
    atualizadoEm: "2026-08-05T12:00:00.000Z",
    syncStatus: "REJECTED",
    ultimoErro: "Conflito de versão.",
    pendingMutationId: "m-1",
  } as never);
}

async function semearMutacao(
  id: string,
  status: string,
  extras: Record<string, unknown> = {},
) {
  const database = await getCortexDb();
  await database.put("outbox_mutations", {
    clientMutationId: id,
    entidadeTipo: "EQUIPE",
    entidadeId: EQUIPE_ID,
    obraId: OBRA_ID,
    operacao: "TRANSITION",
    status,
    baseVersao: 6,
    tentativas: 3,
    retryAttempt: 0,
    nextAttemptAt: null,
    blockedReason: null,
    createdAt: "2026-08-05T12:00:00.000Z",
    updatedAt: "2026-08-05T12:00:00.000Z",
    payload: {},
    ...extras,
  } as never);
}

describe("descartar o que não tem mais como subir", () => {
  beforeEach(async () => {
    vi.stubGlobal("window", new EventTarget());
    setSession({
      colaboradorId: COLABORADOR_ID,
      nome: "Alfa",
      papelAcesso: "ALFA",
      escopoGlobal: true,
      obraIds: [],
      expiraEm: "2099-01-01T00:00:00.000Z",
    });
    databaseName = await databaseNameForScope(COLABORADOR_ID, "ALFA:global");
    await updateSyncState({ deviceId: DEVICE_ID });
  });

  afterEach(async () => {
    await closeCortexDb();
    if (databaseName) await deleteDB(databaseName);
    clearSession();
  });

  /*
   * REJECTED era o único estado sem saída na tela: o descarte recusava tocá-lo
   * e o reenvio devolvia a mesma recusa, porque troca a identidade da mutação
   * mas preserva a versão-base que o servidor rejeitou.
   */
  it("aceita descartar uma mutação rejeitada, não só em conflito", async () => {
    await semearEquipeTravada();
    await semearMutacao("m-1", "REJECTED");

    expect(await descartarEdicaoEmConflito("m-1")).toMatchObject({
      entidadeTipo: "EQUIPE",
      entidadeId: EQUIPE_ID,
    });

    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", "m-1")).toBeUndefined();
  });

  /*
   * A outra metade do beco: replaceLocalTeams e putLocalTeam recusam-se a
   * sobrescrever equipe marcada, e nada limpava a marca. Sem liberá-la, o
   * descarte apagaria o cartão e deixaria a equipe incapaz de aprender a versão
   * do servidor — a recusa voltaria no gesto seguinte.
   */
  it("libera a marca da equipe para a reidratação poder alcançá-la", async () => {
    await semearEquipeTravada();
    await semearMutacao("m-1", "REJECTED");

    await descartarEdicaoEmConflito("m-1");

    const database = await getCortexDb();
    const equipe = await database.get("teams", EQUIPE_ID);
    expect(equipe?.syncStatus).toBe("SYNCED");
    expect(equipe?.pendingMutationId).toBeNull();
    expect(equipe?.ultimoErro).toBeNull();
  });

  /*
   * Havendo outra mutação para a mesma equipe, a marca ainda descreve trabalho
   * real. Liberá-la faria a próxima reidratação apagar uma edição que ainda vai
   * subir.
   */
  it("não libera a marca enquanto sobrar mutação para a mesma equipe", async () => {
    await semearEquipeTravada();
    await semearMutacao("m-1", "REJECTED");
    await semearMutacao("m-2", "PENDING");

    await descartarEdicaoEmConflito("m-1");

    const database = await getCortexDb();
    expect((await database.get("teams", EQUIPE_ID))?.syncStatus)
      .toBe("REJECTED");
  });

  it("descarta em lote as rejeitadas e as em conflito", async () => {
    await semearEquipeTravada();
    await semearMutacao("m-1", "REJECTED");
    await semearMutacao("m-2", "REJECTED");
    await semearMutacao("m-3", "CONFLICT");

    expect(await descartarMutacoesMortas()).toBe(3);

    const database = await getCortexDb();
    expect(await database.getAll("outbox_mutations")).toHaveLength(0);
  });

  /*
   * A regra delicada. Reconciliar um conflito cria uma substituta e deixa a
   * original para trás; varrer REJECTED cegamente apagaria o ancestral de
   * trabalho que ainda vai subir — o oposto de "nada se perde".
   */
  it("não descarta quem tem substituta viva pela marca de superação", async () => {
    await semearEquipeTravada();
    await semearMutacao("m-1", "REJECTED", {
      blockedReason: "SUPERSEDED_BY:m-9",
    });

    expect(await descartarMutacoesMortas()).toBe(0);

    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", "m-1")).toBeDefined();
  });

  it("não descarta quem tem substituta viva por causalidade", async () => {
    await semearEquipeTravada();
    await semearMutacao("m-1", "REJECTED");
    await semearMutacao("m-2", "PENDING", { causationId: "m-1" });

    expect(await descartarMutacoesMortas()).toBe(0);

    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", "m-1")).toBeDefined();
  });

  it("não toca no que ainda está a caminho", async () => {
    await semearEquipeTravada();
    await semearMutacao("m-1", "PENDING");
    await semearMutacao("m-2", "SYNCING");

    expect(await descartarMutacoesMortas()).toBe(0);

    const database = await getCortexDb();
    expect(await database.getAll("outbox_mutations")).toHaveLength(2);
  });
});
