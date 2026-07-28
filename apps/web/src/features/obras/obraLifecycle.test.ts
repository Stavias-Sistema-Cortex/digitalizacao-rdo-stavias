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
  reconcileCanonicalConflict,
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

  it("deriva a cauda pelo grafo/baseVersion quando UUIDs e timestamps não ordenam a cadeia", async () => {
    const uuids = [
      "ffffffff-ffff-4fff-8fff-fffffffffff0",
      "00000000-0000-4000-8000-000000000101",
      "00000000-0000-4000-8000-000000000010",
      "00000000-0000-4000-8000-000000000102",
      "00000000-0000-4000-8000-000000000020",
      "00000000-0000-4000-8000-000000000103",
    ];
    vi.spyOn(crypto, "randomUUID").mockImplementation(
      () => uuids.shift() as `${string}-${string}-${string}-${string}-${string}`,
    );
    const database = await getCortexDb();
    await database.put("obras", obra());

    const updated = await queueUpdateObra(obra(), {
      codigoContrato: "CT-2",
      codigoInterno: null,
      nome: "Obra editada",
      cliente: null,
      descricao: null,
      cidade: null,
      uf: null,
      rodovia: null,
      fonteArquivo: null,
      observacoes: null,
    });
    const deactivated = await queueDeactivateObra(updated);
    for (const mutation of await database.getAll("outbox_mutations")) {
      await database.put("outbox_mutations", {
        ...mutation,
        occurredAt: BASE_TIME,
        criadaNoClienteEm: BASE_TIME,
      });
    }
    await queueArchiveObra(deactivated);

    const queued = await database.getAll("outbox_mutations");
    const archive = queued.find(
      (mutation) => mutation.operacao === "ARQUIVAR_OBRA",
    )!;
    expect(archive.dependsOnMutationIds).toEqual([
      "00000000-0000-4000-8000-000000000010",
    ]);
    expect(archive.baseVersion).toBe(6);
  });

  it("rejeita fila causal ambígua antes de criar nova mutação", async () => {
    const database = await getCortexDb();
    await database.put("obras", obra());
    const updated = await queueUpdateObra(obra(), {
      codigoContrato: "CT-2",
      codigoInterno: null,
      nome: "Obra editada",
      cliente: null,
      descricao: null,
      cidade: null,
      uf: null,
      rodovia: null,
      fonteArquivo: null,
      observacoes: null,
    });
    const deactivated = await queueDeactivateObra(updated);
    const queued = await database.getAll("outbox_mutations");
    const second = queued.find(
      (mutation) => mutation.operacao === "DESATIVAR_OBRA",
    )!;
    await database.put("outbox_mutations", {
      ...second,
      baseVersion: 4,
      baseVersao: 4,
      causationId: null,
      dependsOnMutationIds: [],
    });

    await expect(queueArchiveObra(deactivated)).rejects.toThrow(/ambígua/i);
    expect(await database.getAll("outbox_mutations")).toHaveLength(2);
  });

  it("CAS multiaba aceita uma edição concorrente e rejeita a outra sem duplicar baseVersion", async () => {
    const database = await getCortexDb();
    await database.put("obras", obra());

    const [update, deactivate] = await Promise.allSettled([
      queueUpdateObra(obra(), {
        codigoContrato: "CT-2",
        codigoInterno: null,
        nome: "Obra editada",
        cliente: null,
        descricao: null,
        cidade: null,
        uf: null,
        rodovia: null,
        fonteArquivo: null,
        observacoes: null,
      }),
      queueDeactivateObra(obra()),
    ]);

    expect([update.status, deactivate.status].sort()).toEqual([
      "fulfilled",
      "rejected",
    ]);
    const rejection = [update, deactivate].find(
      (result) => result.status === "rejected",
    ) as PromiseRejectedResult;
    expect(String(rejection.reason)).toMatch(/mudou durante a preparação/i);
    expect(await database.getAll("outbox_mutations")).toHaveLength(1);
  });

  it("bloqueia edição após ERROR canônico e libera somente após recuperação explícita", async () => {
    const database = await getCortexDb();
    await database.put("obras", obra());
    const deactivated = await queueDeactivateObra(obra());
    const mutation = (await database.getAll("outbox_mutations"))[0];
    await database.put("outbox_mutations", {
      ...mutation,
      status: "ERROR",
      ultimoErro: "Falha canônica não aplicada.",
    });

    await expect(queueArchiveObra(deactivated)).rejects.toThrow(
      /recuperação explícita/i,
    );
    expect(await database.getAll("outbox_mutations")).toHaveLength(1);

    await returnMutationToPending(
      mutation.clientMutationId,
      "Reenvio autorizado.",
    );
    const recovered = await database.get("obras", OBRA_ID);
    await expect(queueArchiveObra(recovered!)).resolves.toMatchObject({
      arquivadoEm: expect.any(String),
    });
    const queued = (await database.getAll("outbox_mutations"))
      .sort((left, right) => (left.baseVersion ?? -1) - (right.baseVersion ?? -1));
    expect(queued.map((item) => item.baseVersion)).toEqual([4, 5]);
    expect(queued[1].dependsOnMutationIds).toEqual([
      mutation.clientMutationId,
    ]);
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

  it("projeta SYNCED após aplicar replacement de conflito disjunto", async () => {
    const database = await getCortexDb();
    await database.put("obras", obra());
    const updated = await queueUpdateObra(obra(), {
      codigoContrato: "CT-1",
      codigoInterno: "INT-1",
      nome: "Nome local preservado",
      cliente: "Cliente",
      descricao: "Descrição",
      cidade: "Campinas",
      uf: "SP",
      rodovia: "SP-101",
      fonteArquivo: "cadastro.xlsx",
      observacoes: "Observação",
    });
    const original = (await database.getAll("outbox_mutations"))[0];
    const originalEvent = (await database.getAllFromIndex(
      "operational_events",
      "by-client-mutation-id",
      original.clientMutationId,
    ))[0];
    await markMutationAsSyncing(original);
    await applyPushResultAtomically({
      clientMutationId: original.clientMutationId,
      status: "CONFLITO",
      entidadeTipo: "OBRA",
      entidadeId: OBRA_ID,
      conflito: {
        versaoAtual: 5,
        remoteCompleto: true,
        snapshotRemoto: {
          ...originalEvent.previousState,
          cliente: "Cliente remoto",
        },
      },
      erro: "Conflito disjunto.",
    });

    const replacement = await reconcileCanonicalConflict(
      original.clientMutationId,
      "00000000-0000-4000-8000-000000000021",
      "00000000-0000-4000-8000-000000000022",
      "2026-07-28T12:02:00.000Z",
    );
    expect(replacement).toMatchObject({
      status: "PENDING",
      baseVersion: 5,
      payload: expect.objectContaining({
        nome: updated.nome,
        cliente: "Cliente remoto",
      }),
    });
    await markMutationAsSyncing(replacement!);
    await applyPushResultAtomically({
      clientMutationId: replacement!.clientMutationId,
      status: "APLICADA",
      entidadeTipo: "OBRA",
      entidadeId: OBRA_ID,
      eventoServidorCommitSeq: 42,
      resultado: {
        ...replacement!.payload,
        versaoEntidade: 6,
        atualizadoEm: "2026-07-28T12:03:00.000Z",
      },
    });

    expect(await database.get("obras", OBRA_ID)).toMatchObject({
      nome: "Nome local preservado",
      cliente: "Cliente remoto",
      versaoEntidade: 6,
      syncStatus: "SYNCED",
      ultimoErro: null,
    });
  });

  it("mantém descendentes coerentes em hold seguro em vez de sobrescrever a projeção", async () => {
    const database = await getCortexDb();
    await database.put("obras", obra());
    const updated = await queueUpdateObra(obra(), {
      codigoContrato: "CT-1",
      codigoInterno: "INT-1",
      nome: "Nome local preservado",
      cliente: "Cliente",
      descricao: "Descrição",
      cidade: "Campinas",
      uf: "SP",
      rodovia: "SP-101",
      fonteArquivo: "cadastro.xlsx",
      observacoes: "Observação",
    });
    const deactivated = await queueDeactivateObra(updated);
    const queued = await database.getAll("outbox_mutations");
    const original = queued.find(
      (mutation) => mutation.operacao === "ATUALIZAR_OBRA",
    )!;
    const descendant = queued.find(
      (mutation) => mutation.operacao === "DESATIVAR_OBRA",
    )!;
    const originalEvent = (await database.getAllFromIndex(
      "operational_events",
      "by-client-mutation-id",
      original.clientMutationId,
    ))[0];
    await markMutationAsSyncing(original);
    await applyPushResultAtomically({
      clientMutationId: original.clientMutationId,
      status: "CONFLITO",
      entidadeTipo: "OBRA",
      entidadeId: OBRA_ID,
      conflito: {
        versaoAtual: 5,
        remoteCompleto: true,
        snapshotRemoto: {
          ...originalEvent.previousState,
          cliente: "Cliente remoto",
        },
      },
      erro: "Conflito com descendente.",
    });

    await expect(
      reconcileCanonicalConflict(original.clientMutationId),
    ).resolves.toBeNull();
    expect(await database.get("obras", OBRA_ID)).toMatchObject({
      nome: deactivated.nome,
      status: "INATIVA",
      syncStatus: "CONFLICT",
    });
    expect(
      await database.get("outbox_mutations", descendant.clientMutationId),
    ).toMatchObject({
      status: "PENDING",
      dependsOnMutationIds: [original.clientMutationId],
    });
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
