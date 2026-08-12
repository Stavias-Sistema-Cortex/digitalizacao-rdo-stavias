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
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import type { LocalRdoRecord } from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import { applyPulledEventsAtomically } from "./syncStorage";

/**
 * O evento RDO_APAGADO precisa remover a cópia local — era ignorado.
 *
 * <p>Apagar um RDO é exclusão de verdade no servidor, e o aparelho de quem
 * apagou se limpa por conta própria. Todo outro aparelho da obra recebia este
 * evento no pull e não fazia nada com ele, porque o tratamento conhecia só
 * enviado, cancelado e restaurado. O RDO apagado ficava imortal nas outras
 * máquinas, com o selo SINCRONIZADO — visível para todo mundo, menos para quem
 * o apagou, que era justamente quem podia notar.
 */

const USER_ID = "00000000-0000-4000-8000-000000000171";
const OBRA_ID = "00000000-0000-4000-8000-000000000172";
const RDO_ID = "00000000-0000-4000-8000-000000000179";

let databaseName = "";

function rdoLocal(
  overrides: Partial<LocalRdoRecord> = {},
): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-0025",
    dataRdo: "2026-08-11",
    statusRdo: "RASCUNHO",
    canceladoEm: null,
    syncStatus: "SYNCED",
    versaoEntidade: 3,
    payload: { observacoes: "vindo do servidor" },
    createdAt: "2026-08-11T10:00:00.000Z",
    updatedAt: "2026-08-11T10:00:00.000Z",
    ...overrides,
  } as LocalRdoRecord;
}

function eventoDeApagamento(commitSeq: number) {
  return {
    commitSeq,
    eventoId: `00000000-0000-4000-8000-0000000001${80 + commitSeq}`,
    tipoEvento: "RDO_APAGADO",
    entidadeTipo: "RDO",
    entidadeId: RDO_ID,
    ocorridoEmUtc: "2026-08-11T21:00:00.000Z",
    payload: { id: RDO_ID, obraId: OBRA_ID, status: "APAGADO" },
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  vi.stubGlobal("BroadcastChannel", undefined);
  setSession({
    colaboradorId: USER_ID,
    nome: "Rafael",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(
    USER_ID,
    `BETA:${OBRA_ID}`,
  );
  const database = await getCortexDb();
  await database.put("sync_state", {
    key: "default",
    deviceId: null,
    usuarioId: USER_ID,
    lastPulledCommitSeq: 0,
    lastAckedCommitSeq: 0,
    isSyncing: false,
    lastSyncStartedAt: null,
    lastSyncCompletedAt: null,
    lastSyncError: null,
    syncExecutionLease: null,
  });
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) {
    await deleteDB(databaseName);
  }
  clearSession();
  vi.unstubAllGlobals();
});

describe("RDO_APAGADO no pull", () => {
  it("remove do aparelho a cópia sincronizada do RDO apagado", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoLocal());

    const cursor = await applyPulledEventsAtomically(
      [eventoDeApagamento(7)],
      7,
    );

    expect(await database.get("rdos", RDO_ID)).toBeUndefined();
    // O evento conta como processado e o cursor anda: o apagamento não pode
    // voltar na janela seguinte como novidade.
    expect(cursor).toBe(7);
    expect(await database.get("processed_events", 7)).toBeTruthy();
  });

  /*
   * Trabalho de campo pendente não sai por evento. O envio dele vai receber do
   * servidor a resposta de que o RDO não existe, e é a fila — com os reparos
   * que ela já tem — que decide o destino. Regra antiga, preservada.
   */
  it("não remove a cópia que carrega trabalho local pendente", async () => {
    const database = await getCortexDb();
    await database.put(
      "rdos",
      rdoLocal({ syncStatus: "PENDING_SYNC" }),
    );

    await applyPulledEventsAtomically([eventoDeApagamento(8)], 8);

    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      id: RDO_ID,
      syncStatus: "PENDING_SYNC",
    });
  });

  it("segue aplicando o cancelamento como atualização, não como remoção", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoLocal());

    await applyPulledEventsAtomically(
      [
        {
          commitSeq: 9,
          eventoId: "00000000-0000-4000-8000-000000000199",
          tipoEvento: "RDO_CANCELADO",
          entidadeTipo: "RDO",
          entidadeId: RDO_ID,
          ocorridoEmUtc: "2026-08-11T22:00:00.000Z",
          payload: { id: RDO_ID, status: "CANCELADA" },
        },
      ],
      9,
    );

    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      statusRdo: "CANCELADA",
      canceladoEm: "2026-08-11T22:00:00.000Z",
    });
  });

  it("um RDO que este aparelho nunca conheceu não ressuscita pelo evento", async () => {
    await applyPulledEventsAtomically([eventoDeApagamento(10)], 10);

    const database = await getCortexDb();
    expect(await database.get("rdos", RDO_ID)).toBeUndefined();
  });
});
