import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../auth/authSession";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import type { LocalRdoRecord } from "../../lib/db/db.types";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import { updateSyncState } from "../../lib/db/syncStateRepository";
import {
  applyPulledEventsAtomically,
  markMutationAsSyncing,
  rejectMutationLocally,
} from "../../lib/sync/syncStorage";
import { queueCancelRdo, queueRestoreRdo } from "./rdoLifecycle";

const RDO_ID = "00000000-0000-4000-8000-0000000000a1";
const OBRA_ID = "00000000-0000-4000-8000-0000000000a2";
const USER_ID = "00000000-0000-4000-8000-0000000000a3";
const DEVICE_ID = "00000000-0000-4000-8000-0000000000a4";
const BASE_TIME = "2026-08-01T12:00:00.000Z";

let databaseName = "";

function rdo(
  overrides: Partial<LocalRdoRecord> = {},
): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-014",
    dataRdo: "2026-08-01",
    statusRdo: "ENVIADO",
    syncStatus: "SYNCED",
    versaoEntidade: 3,
    payload: { obraId: OBRA_ID, numeroRdo: "RDO-014" },
    createdAt: BASE_TIME,
    updatedAt: BASE_TIME,
    canceladoEm: null,
    ...overrides,
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  setSession({
    colaboradorId: USER_ID,
    nome: "Apontador",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(USER_ID, `BETA:${OBRA_ID}`);
  await updateSyncState({
    deviceId: DEVICE_ID,
    usuarioId: USER_ID,
    lastPulledCommitSeq: 0,
    lastAckedCommitSeq: 0,
  });
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("apagar e recuperar RDO", () => {
  it("enfileira o apagamento e marca o registro sem destruí-lo", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdo());

    const apagado = await queueCancelRdo(rdo());

    expect(apagado.canceladoEm).not.toBeNull();
    expect(apagado.statusRdo).toBe("CANCELADA");
    // O conteúdo do lançamento continua inteiro: apagar é marcar, não remover.
    expect(apagado.payload).toEqual(rdo().payload);
    expect(await database.get("rdos", RDO_ID)).toEqual(apagado);

    const [mutacao] = await database.getAll("outbox_mutations");
    expect(mutacao).toMatchObject({
      entidadeTipo: "RDO",
      entidadeId: RDO_ID,
      operacao: "CANCELAR_RDO",
      operation: "DELETE",
      baseVersao: 3,
      status: "PENDING",
    });
  });

  it("encadeia a recuperação depois do apagamento ainda na fila", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdo());

    const apagado = await queueCancelRdo(rdo());
    const recuperado = await queueRestoreRdo(apagado);

    expect(recuperado.canceladoEm).toBeNull();

    const fila = (await database.getAll("outbox_mutations"))
      .sort((left, right) => left.baseVersao! - right.baseVersao!);
    expect(fila.map((mutacao) => mutacao.operacao)).toEqual([
      "CANCELAR_RDO",
      "RESTAURAR_RDO",
    ]);
    expect(fila.map((mutacao) => mutacao.baseVersao)).toEqual([3, 4]);
    expect(fila[1].causationId).toBe(fila[0].clientMutationId);
  });

  it("recusa apagar um RDO que o servidor ainda não conhece", async () => {
    const database = await getCortexDb();
    const local = rdo({ versaoEntidade: null, syncStatus: "PENDING_SYNC" });
    await database.put("rdos", local);

    await expect(queueCancelRdo(local)).rejects.toThrow(
      /versão autoritativa/,
    );
    expect(await database.getAll("outbox_mutations")).toHaveLength(0);
  });

  it("não empilha alteração sobre uma recusa não resolvida", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdo());
    await queueCancelRdo(rdo());
    const [mutacao] = await database.getAll("outbox_mutations");
    await markMutationAsSyncing(mutacao);
    await rejectMutationLocally(
      mutacao.clientMutationId,
      "PAYLOAD_INVALIDO",
      "payload inválido.",
    );

    await expect(
      queueRestoreRdo((await database.get("rdos", RDO_ID))!),
    ).rejects.toThrow(/recusada/);
  });

  it("converge com o estado que o servidor devolve na recuperação", async () => {
    // O palpite local do apagamento era CANCELADA; o servidor deriva o estado
    // de volta de `enviado_em`, e é o dele que vale.
    const database = await getCortexDb();
    await database.put("rdos", rdo({ statusRdo: "CANCELADA" }));

    await applyPulledEventsAtomically([
      {
        commitSeq: 1,
        eventoId: crypto.randomUUID(),
        tipoEvento: "RDO_RESTAURADO",
        entidadeTipo: "RDO",
        entidadeId: RDO_ID,
        versaoEntidade: 5,
        payload: { rdoId: RDO_ID, status: "ENVIADO" },
      },
    ], 1);

    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      statusRdo: "ENVIADO",
      canceladoEm: null,
      versaoEntidade: 5,
    });
  });

  it("marca como apagado o que outro dispositivo apagou", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdo());

    await applyPulledEventsAtomically([
      {
        commitSeq: 1,
        eventoId: crypto.randomUUID(),
        tipoEvento: "RDO_CANCELADO",
        entidadeTipo: "RDO",
        entidadeId: RDO_ID,
        ocorridoEmUtc: "2026-08-01T18:30:00.000Z",
        versaoEntidade: 4,
        payload: { rdoId: RDO_ID, status: "CANCELADA" },
      },
    ], 1);

    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      statusRdo: "CANCELADA",
      canceladoEm: "2026-08-01T18:30:00.000Z",
    });
  });
});
