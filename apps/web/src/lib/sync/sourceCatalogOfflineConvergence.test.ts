import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import type { RdoCreationContextCacheRecord } from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import { applyPulledEventsAtomically } from "./syncStorage";

const USER_ID = "00000000-0000-4000-8000-000000000251";
const OBRA_ID = "00000000-0000-4000-8000-000000000252";
const ASSET_ID = "00000000-0000-4000-8000-000000000253";
const COLLABORATOR_ID = "00000000-0000-4000-8000-000000000254";
let databaseName = "";

function cachedContext(): RdoCreationContextCacheRecord {
  const coverage = {
    previousWorkforce: { status: "COMPLETE", total: 0, returned: 0, complete: true },
    programacoes: { status: "COMPLETE", total: 0, returned: 0, complete: true },
    colaboradores: { status: "COMPLETE", total: 1, returned: 1, complete: true },
    equipamentos: { status: "COMPLETE", total: 1, returned: 1, complete: true },
    serviceCatalog: { status: "COMPLETE", total: 0, returned: 0, complete: true },
    priceCatalog: { status: "COMPLETE", total: 0, returned: 0, complete: true },
  };
  return {
    ownerId: USER_ID,
    obraId: OBRA_ID,
    selectedDate: "2026-08-18",
    sourceVersion: 10,
    receiptVersion: 4,
    cachedAt: "2026-08-18T12:00:00.000Z",
    coverage,
    context: {
      obra: { id: OBRA_ID },
      data: "2026-08-18",
      colaboradores: [{
        id: COLLABORATOR_ID,
        codigoColaborador: "17",
        nome: "Paulo",
        papelNaObra: null,
        nomePerfil: null,
        funcao: "Operador",
        naObra: true,
      }],
      equipamentos: [{
        id: ASSET_ID,
        codigoExterno: "EQ-01",
        nome: "Escavadeira antiga",
        categoria: "ESCAVADEIRA",
        naObra: true,
      }],
      coverage,
      freshness: {
        status: "FRESH",
        sourceVersion: 10,
        generatedAt: "2026-08-18T12:00:00.000Z",
        staleAfter: "2026-08-18T12:15:00.000Z",
      },
      provenance: {
        receiptVersion: 4,
        sourceVersion: 10,
        worksiteId: OBRA_ID,
        selectedDate: "2026-08-18",
        previousRdoId: null,
        generatedAt: "2026-08-18T12:00:00.000Z",
      },
    },
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  vi.stubGlobal("BroadcastChannel", undefined);
  setSession({
    colaboradorId: USER_ID,
    nome: "Encarregada",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(USER_ID, `BETA:${OBRA_ID}`);
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
  await database.put("rdo_creation_contexts", cachedContext());
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
  vi.unstubAllGlobals();
});

it("applies a Zeladoria asset modification to the offline RDO catalog before ack", async () => {
  await applyPulledEventsAtomically([{
    commitSeq: 1,
    eventoId: "00000000-0000-4000-8000-000000000261",
    tipoEvento: "ATIVO_ATUALIZADO_DO_LEGADO",
    entidadeTipo: "ATIVO",
    entidadeId: ASSET_ID,
    ocorridoEmUtc: "2026-08-18T12:05:00.000Z",
    payload: {
      assetId: ASSET_ID,
      prefixo: "EQ-99",
      tipo: "RETROESCAVADEIRA",
      nome: "Escavadeira atualizada",
    },
  }], 1);

  const stored = await (await getCortexDb()).get(
    "rdo_creation_contexts",
    [USER_ID, OBRA_ID, "2026-08-18"],
  );
  expect((stored?.context.equipamentos as Array<Record<string, unknown>>)[0])
    .toMatchObject({
      id: ASSET_ID,
      codigoExterno: "EQ-99",
      nome: "Escavadeira atualizada",
      categoria: "RETROESCAVADEIRA",
      naObra: true,
    });
});

it("removes an absent Zeladoria asset from the offline selector atomically with ack", async () => {
  await applyPulledEventsAtomically([{
    commitSeq: 1,
    eventoId: "00000000-0000-4000-8000-000000000262",
    tipoEvento: "ATIVO_EXCLUIDO_DA_ORIGEM",
    entidadeTipo: "ATIVO",
    entidadeId: ASSET_ID,
    ocorridoEmUtc: "2026-08-18T12:05:00.000Z",
    payload: { active: false },
  }], 1);

  const stored = await (await getCortexDb()).get(
    "rdo_creation_contexts",
    [USER_ID, OBRA_ID, "2026-08-18"],
  );
  expect(stored?.context.equipamentos).toEqual([]);
  expect(stored?.coverage.equipamentos).toMatchObject({ total: 0, returned: 0 });
  expect(await (await getCortexDb()).get("processed_events", 1)).toBeTruthy();
});

it("removes an explicitly inactive Academy collaborator without losing another offline context", async () => {
  await applyPulledEventsAtomically([{
    commitSeq: 1,
    eventoId: "00000000-0000-4000-8000-000000000263",
    tipoEvento: "COLABORADOR_ATUALIZADO_DO_LEGADO",
    entidadeTipo: "COLABORADOR",
    entidadeId: COLLABORATOR_ID,
    ocorridoEmUtc: "2026-08-18T12:05:00.000Z",
    payload: { colaboradorId: COLLABORATOR_ID, ativo: false },
  }], 1);

  const stored = await (await getCortexDb()).get(
    "rdo_creation_contexts",
    [USER_ID, OBRA_ID, "2026-08-18"],
  );
  expect(stored?.context.colaboradores).toEqual([]);
  expect(stored?.context.equipamentos).toHaveLength(1);
});

it("updates Academy funcao offline and preserves the manual fallback when the source omits it", async () => {
  await applyPulledEventsAtomically([{
    commitSeq: 1,
    eventoId: "00000000-0000-4000-8000-000000000264",
    tipoEvento: "COLABORADOR_ATUALIZADO_DO_LEGADO",
    entidadeTipo: "COLABORADOR",
    entidadeId: COLLABORATOR_ID,
    ocorridoEmUtc: "2026-08-18T12:05:00.000Z",
    payload: {
      colaboradorId: COLLABORATOR_ID,
      nome: "Paulo atualizado",
      funcao: null,
      ativo: true,
    },
  }], 1);

  let stored = await (await getCortexDb()).get(
    "rdo_creation_contexts",
    [USER_ID, OBRA_ID, "2026-08-18"],
  );
  expect((stored?.context.colaboradores as Array<Record<string, unknown>>)[0])
    .toMatchObject({ nome: "Paulo atualizado", funcao: "Operador" });

  await applyPulledEventsAtomically([{
    commitSeq: 2,
    eventoId: "00000000-0000-4000-8000-000000000265",
    tipoEvento: "COLABORADOR_ATUALIZADO_DO_LEGADO",
    entidadeTipo: "COLABORADOR",
    entidadeId: COLLABORATOR_ID,
    ocorridoEmUtc: "2026-08-18T12:06:00.000Z",
    payload: {
      colaboradorId: COLLABORATOR_ID,
      funcao: "Pedreiro",
      ativo: true,
    },
  }], 2);

  stored = await (await getCortexDb()).get(
    "rdo_creation_contexts",
    [USER_ID, OBRA_ID, "2026-08-18"],
  );
  expect((stored?.context.colaboradores as Array<Record<string, unknown>>)[0])
    .toMatchObject({ nome: "Paulo atualizado", funcao: "Pedreiro" });
});
