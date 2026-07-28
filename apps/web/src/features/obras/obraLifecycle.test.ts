import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../auth/authSession";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import type { ObraLocalRecord } from "../../lib/db/db.types";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import { updateSyncState } from "../../lib/db/syncStateRepository";
import {
  applyPulledEventsAtomically,
  applyPushResultAtomically,
  markMutationAsSyncing,
  returnMutationToPending,
} from "../../lib/sync/syncStorage";
import {
  queueArchiveObra,
  queueDeactivateObra,
  queueRestoreObra,
  queueUpdateObra,
} from "./obraLifecycle";

const OBRA_ID = "00000000-0000-4000-8000-000000000001";
const USER_ID = "00000000-0000-4000-8000-000000000002";
const DEVICE_ID = "00000000-0000-4000-8000-000000000003";
const BASE_TIME = "2026-07-28T12:00:00.000Z";

let databaseName = "";

function obra(
  overrides: Partial<ObraLocalRecord> = {},
): ObraLocalRecord {
  return {
    id: OBRA_ID,
    codigoContrato: "CT-1",
    codigoInterno: "INT-1",
    nome: "Obra Alfa",
    cliente: "Cliente",
    descricao: "Descrição",
    cidade: "Campinas",
    uf: "SP",
    rodovia: "SP-101",
    fonteArquivo: "cadastro.xlsx",
    status: "ATIVA",
    observacoes: "Observação",
    latitude: null,
    longitude: null,
    valorContratual: 1000,
    versaoEntidade: 4,
    arquivadoEm: null,
    syncStatus: "SYNCED",
    ultimoErro: null,
    updatedAt: BASE_TIME,
    ...overrides,
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  setSession({
    colaboradorId: USER_ID,
    nome: "Administrador",
    papelAcesso: "ALFA",
    escopoGlobal: true,
    obraIds: [],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(USER_ID, "ALFA:GLOBAL");
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

describe("obra lifecycle queue", () => {
  it("aplica as quatro projeções e encadeia baseVersion pela cauda pendente", async () => {
    const database = await getCortexDb();
    await database.put("obras", obra());

    const updated = await queueUpdateObra(obra(), {
      codigoContrato: "CT-2",
      codigoInterno: "INT-2",
      nome: "Obra editada",
      cliente: "Novo cliente",
      descricao: "Nova descrição",
      cidade: "Limeira",
      uf: "SP",
      rodovia: "SP-147",
      fonteArquivo: null,
      observacoes: null,
    });
    const deactivated = await queueDeactivateObra(updated);
    const archived = await queueArchiveObra(deactivated);
    const restored = await queueRestoreObra(archived);

    expect(updated.nome).toBe("Obra editada");
    expect(deactivated.status).toBe("INATIVA");
    expect(archived.arquivadoEm).not.toBeNull();
    expect(restored).toMatchObject({
      status: "INATIVA",
      arquivadoEm: null,
      syncStatus: "PENDING_SYNC",
      ultimoErro: null,
    });
    expect(await database.get("obras", OBRA_ID)).toEqual(restored);

    const queued = (await database.getAll("outbox_mutations"))
      .sort((left, right) => left.baseVersao! - right.baseVersao!);
    expect(queued.map((mutation) => mutation.operacao)).toEqual([
      "ATUALIZAR_OBRA",
      "DESATIVAR_OBRA",
      "ARQUIVAR_OBRA",
      "RESTAURAR_OBRA",
    ]);
    expect(queued.map((mutation) => mutation.baseVersao)).toEqual([
      4, 5, 6, 7,
    ]);
    expect(queued.map((mutation) => mutation.dependsOnMutationIds)).toEqual([
      [],
      [queued[0].clientMutationId],
      [queued[1].clientMutationId],
      [queued[2].clientMutationId],
    ]);
    expect(queued.map((mutation) => mutation.causationId)).toEqual([
      null,
      queued[0].clientMutationId,
      queued[1].clientMutationId,
      queued[2].clientMutationId,
    ]);
    expect(queued.every((mutation) =>
      mutation.payload.id === OBRA_ID &&
      mutation.payload.obraId === OBRA_ID
    )).toBe(true);
  });

  it("reconcilia confirmação, replay e conflito sem perder snapshot/evento/outbox", async () => {
    const database = await getCortexDb();
    await database.put("obras", obra());

    const archived = await queueArchiveObra(obra());
    const archiveMutation = (await database.getAll("outbox_mutations"))[0];
    await markMutationAsSyncing(archiveMutation);
    expect(await database.get("obras", OBRA_ID)).toMatchObject({
      arquivadoEm: archived.arquivadoEm,
      syncStatus: "SYNCING",
    });

    await applyPushResultAtomically({
      clientMutationId: archiveMutation.clientMutationId,
      status: "APLICADA",
      entidadeTipo: "OBRA",
      entidadeId: OBRA_ID,
      eventoServidorCommitSeq: 41,
      resultado: {
        id: OBRA_ID,
        obraId: OBRA_ID,
        codigoContrato: "CT-1",
        codigoInterno: "INT-1",
        nome: "Obra Alfa",
        cliente: "Cliente",
        descricao: "Descrição",
        cidade: "Campinas",
        uf: "SP",
        rodovia: "SP-101",
        fonteArquivo: "cadastro.xlsx",
        status: "ATIVA",
        observacoes: "Observação",
        arquivadoEm: archived.arquivadoEm,
        atualizadoEm: "2026-07-28T12:01:00.000Z",
        versaoEntidade: 5,
      },
    });
    const confirmed = await database.get("obras", OBRA_ID);
    expect(confirmed).toMatchObject({
      arquivadoEm: archived.arquivadoEm,
      versaoEntidade: 5,
      syncStatus: "SYNCED",
      ultimoErro: null,
    });

    const restored = await queueRestoreObra(confirmed!);
    const restoreMutation = (await database.getAll("outbox_mutations"))
      .find((mutation) => mutation.operacao === "RESTAURAR_OBRA")!;
    await markMutationAsSyncing(restoreMutation);
    await returnMutationToPending(
      restoreMutation.clientMutationId,
      "Rede indisponível.",
    );
    expect(await database.get("obras", OBRA_ID)).toMatchObject({
      arquivadoEm: null,
      syncStatus: "PENDING_SYNC",
      ultimoErro: "Rede indisponível.",
    });
    const replayable = (await database.get(
      "outbox_mutations",
      restoreMutation.clientMutationId,
    ))!;
    await markMutationAsSyncing(replayable);
    await applyPushResultAtomically({
      clientMutationId: restoreMutation.clientMutationId,
      status: "CONFLITO",
      entidadeTipo: "OBRA",
      entidadeId: OBRA_ID,
      conflito: {
        versaoAtual: 6,
        snapshot: {
          id: OBRA_ID,
          obraId: OBRA_ID,
          codigoContrato: "CT-REMOTO",
          nome: "Obra remota",
          status: "ATIVA",
          arquivadoEm: archived.arquivadoEm,
        },
      },
      erro: "Conflito concorrente.",
    });

    expect(await database.get("obras", OBRA_ID)).toMatchObject({
      codigoContrato: restored.codigoContrato,
      nome: restored.nome,
      status: restored.status,
      arquivadoEm: null,
      versaoEntidade: 6,
      syncStatus: "CONFLICT",
      ultimoErro: "Conflito concorrente.",
    });
    expect(
      await database.get("outbox_mutations", restoreMutation.clientMutationId),
    ).toMatchObject({ status: "CONFLICT", ultimoErro: "Conflito concorrente." });
    expect(
      await database.getAllFromIndex(
        "operational_events",
        "by-client-mutation-id",
        restoreMutation.clientMutationId,
      ),
    ).toEqual([
      expect.objectContaining({
        type: "OBRA_RESTAURADA",
        result: "CONFLICT",
        newState: expect.objectContaining({ arquivadoEm: null }),
      }),
    ]);
  });

  it("aplica eventos pull de arquivar e restaurar sem apagar a obra", async () => {
    const database = await getCortexDb();
    await database.put("obras", obra());

    await applyPulledEventsAtomically([
      {
        commitSeq: 1,
        eventoId: crypto.randomUUID(),
        tipoEvento: "OBRA_ARQUIVADA",
        entidadeTipo: "OBRA",
        entidadeId: OBRA_ID,
        versaoEntidade: 5,
        payload: {
          obraId: OBRA_ID,
          codigoContrato: "CT-1",
          nome: "Obra Alfa",
          status: "ATIVA",
          arquivadoEm: "2026-07-28T13:00:00.000Z",
          versaoLinha: 5,
        },
      },
      {
        commitSeq: 2,
        eventoId: crypto.randomUUID(),
        tipoEvento: "OBRA_RESTAURADA",
        entidadeTipo: "OBRA",
        entidadeId: OBRA_ID,
        versaoEntidade: 6,
        payload: {
          obraId: OBRA_ID,
          codigoContrato: "CT-1",
          nome: "Obra Alfa",
          status: "ATIVA",
          arquivadoEm: null,
          versaoLinha: 6,
        },
      },
    ], 2);

    expect(await database.get("obras", OBRA_ID)).toMatchObject({
      id: OBRA_ID,
      arquivadoEm: null,
      versaoEntidade: 6,
      syncStatus: "SYNCED",
    });
    expect(await database.getAll("processed_events")).toHaveLength(2);
  });
});
