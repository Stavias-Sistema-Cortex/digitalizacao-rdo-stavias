import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import type {
  CanonicalOutboxMutationRecord,
  LocalRdoRecord,
  ObraGeometriaLocalRecord,
  ObraLocalRecord,
  RdoAttachmentRecord,
} from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import { updateSyncState } from "../db/syncStateRepository";
import { commitLocalMutation } from "./localMutationCoordinator";
import { isCanonicalOutboxMutation } from "./mutationEnvelope";
import {
  assertCanonicalMutationEventProvenance,
  isRejeicaoDeObraInexistenteNoServidor,
  markMutationAsSyncing,
  reidentificarObrasInexistentesForSync,
  rejectMutationLocally,
} from "./syncStorage";

const OBRA_MORTA_ID = "00000000-0000-4000-8000-0000000000aa";
const OBRA_VIVA_ID = "00000000-0000-4000-8000-0000000000bb";
const USER_ID = "00000000-0000-4000-8000-000000000002";
const DEVICE_ID = "00000000-0000-4000-8000-000000000003";
const RECUSA = "Obra não encontrada neste servidor.";

let databaseName = "";

function obra(
  id: string,
  overrides: Partial<ObraLocalRecord> = {},
): ObraLocalRecord {
  return {
    id,
    codigoContrato: "CT-77",
    codigoInterno: null,
    nome: "Intervias",
    cliente: "Concessionária",
    descricao: null,
    cidade: "Limeira",
    uf: "SP",
    rodovia: "SP-147",
    fonteArquivo: null,
    status: "ATIVA",
    observacoes: null,
    latitude: null,
    longitude: null,
    valorContratual: null,
    versaoEntidade: 1,
    arquivadoEm: null,
    syncStatus: "SYNCED",
    ultimoErro: null,
    updatedAt: "2026-08-01T09:00:00.000Z",
    ...overrides,
  };
}

function rdoLocal(
  id: string,
  overrides: Partial<LocalRdoRecord> = {},
): LocalRdoRecord {
  return {
    id,
    obraId: OBRA_MORTA_ID,
    programacaoId: null,
    numeroRdo: "RDO-9",
    dataRdo: "2026-08-01",
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: null,
    payload: {
      id,
      obraId: OBRA_MORTA_ID,
      contrato: "CT-ANTIGO",
      programacaoId: null,
    },
    createdAt: "2026-08-01T10:00:00.000Z",
    updatedAt: "2026-08-01T10:00:00.000Z",
    ...overrides,
  } as LocalRdoRecord;
}

async function criacaoRecusada(rdoId: string): Promise<string> {
  const rdo = rdoLocal(rdoId);
  const committed = await commitLocalMutation({
    deviceId: DEVICE_ID,
    userId: USER_ID,
    obraId: OBRA_MORTA_ID,
    entityType: "RDO",
    entityId: rdoId,
    operation: "CREATE",
    transportOperation: "CRIAR_RDO",
    baseVersion: null,
    occurredAt: "2026-08-01T10:00:00.000Z",
    previousSnapshot: {},
    nextSnapshot: { ...rdo.payload },
    principalSnapshot: { ...rdo },
    expectedPrincipalSnapshot: null,
    eventType: "RDO_CRIADO",
    colaboradorId: USER_ID,
    relatedEntities: [{ tipo: "OBRA", id: OBRA_MORTA_ID, nome: "CT-ANTIGO" }],
    write: () => [{ store: "rdos", value: rdo, principal: true }],
  });
  await markMutationAsSyncing(committed.mutation);
  await rejectMutationLocally(
    committed.mutation.clientMutationId,
    "OBRA_NAO_ENCONTRADA",
    RECUSA,
  );
  return committed.mutation.clientMutationId;
}

interface CancelamentoRecusado {
  cancelamentoId: string;
  rdo: LocalRdoRecord;
}

async function cancelamentoRecusado(
  rdoId: string,
): Promise<CancelamentoRecusado> {
  const database = await getCortexDb();
  const conhecido = rdoLocal(rdoId, {
    versaoEntidade: 3,
    syncStatus: "SYNCED",
  });
  await database.put("rdos", conhecido);
  const cancelado: LocalRdoRecord = {
    ...conhecido,
    statusRdo: "CANCELADA",
    canceladoEm: "2026-08-01T11:00:00.000Z",
    syncStatus: "PENDING_SYNC",
    updatedAt: "2026-08-01T11:00:00.000Z",
  };
  const committed = await commitLocalMutation({
    deviceId: DEVICE_ID,
    userId: USER_ID,
    obraId: OBRA_MORTA_ID,
    entityType: "RDO",
    entityId: rdoId,
    operation: "DELETE",
    transportOperation: "CANCELAR_RDO",
    baseVersion: 3,
    occurredAt: "2026-08-01T11:00:00.000Z",
    previousSnapshot: {
      id: rdoId,
      obraId: OBRA_MORTA_ID,
      status: "RASCUNHO",
      canceladoEm: null,
    },
    nextSnapshot: {
      id: rdoId,
      obraId: OBRA_MORTA_ID,
      status: "CANCELADA",
      canceladoEm: "2026-08-01T11:00:00.000Z",
    },
    principalSnapshot: { ...cancelado },
    expectedPrincipalSnapshot: { ...conhecido },
    eventType: "RDO_CANCELADO",
    colaboradorId: USER_ID,
    relatedEntities: [{ tipo: "OBRA", id: OBRA_MORTA_ID }],
    write: () => [{ store: "rdos", value: cancelado, principal: true }],
  });
  await markMutationAsSyncing(committed.mutation);
  await rejectMutationLocally(
    committed.mutation.clientMutationId,
    "OBRA_NAO_ENCONTRADA",
    RECUSA,
  );
  return {
    cancelamentoId: committed.mutation.clientMutationId,
    rdo: cancelado,
  };
}

function canonical(
  mutation: unknown,
): CanonicalOutboxMutationRecord {
  if (
    !mutation ||
    !isCanonicalOutboxMutation(mutation as CanonicalOutboxMutationRecord)
  ) {
    throw new Error("A mutação esperada não é canônica.");
  }
  return mutation as CanonicalOutboxMutationRecord;
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

describe("reidentificação da obra que o servidor diz não existir", () => {
  it("reconhece exatamente a recusa de inexistência", () => {
    expect(isRejeicaoDeObraInexistenteNoServidor(RECUSA)).toBe(true);
    expect(
      isRejeicaoDeObraInexistenteNoServidor(
        "Obra não encontrada ou arquivada.",
      ),
    ).toBe(false);
    expect(isRejeicaoDeObraInexistenteNoServidor(null)).toBe(false);
  });

  it("devolve a criação à fila do recibo de contexto, já sob a obra gêmea", async () => {
    const database = await getCortexDb();
    await database.put("obras", obra(OBRA_MORTA_ID));
    await database.put("obras", obra(OBRA_VIVA_ID));
    const rdoId = crypto.randomUUID();
    const clientMutationId = await criacaoRecusada(rdoId);
    const anexo: RdoAttachmentRecord = {
      id: crypto.randomUUID(),
      rdoId,
      obraId: OBRA_MORTA_ID,
      tipo: "FOTO",
      nome: "foto.jpg",
      nomeOriginal: null,
      mimeType: "image/jpeg",
      tamanhoBytes: 1,
      tamanhoOriginalBytes: 1,
      tamanhoComprimidoBytes: 1,
      arquivo: new Blob(["x"]),
      syncStatus: "SYNC_FAILED",
      ultimoErro: null,
      metadata: {},
      createdAt: "2026-08-01T10:00:00.000Z",
      updatedAt: "2026-08-01T10:00:00.000Z",
      removedAt: null,
    };
    await database.put("rdo_attachments", anexo);

    expect(await reidentificarObrasInexistentesForSync()).toBe(1);

    const mutation = canonical(
      await database.get("outbox_mutations", clientMutationId),
    );
    // O envelope é imutável: a criação não muda de conteúdo aqui — ela volta
    // à fila bloqueada e o pipeline do recibo cunha a substituição.
    expect(mutation).toMatchObject({
      status: "PENDING",
      blockedReason: "RDO_CREATION_CONTEXT_REQUIRED",
      lastSafeCode: "OBRA_REIDENTIFICADA_NO_CLIENTE",
      obraId: OBRA_MORTA_ID,
    });

    expect(await database.get("rdos", rdoId)).toMatchObject({
      obraId: OBRA_VIVA_ID,
      programacaoId: null,
      syncStatus: "PENDING_SYNC",
      payload: {
        obraId: OBRA_VIVA_ID,
        contrato: "CT-77",
        programacaoId: null,
      },
    });
    expect(
      await database.get("rdo_attachments", anexo.id),
    ).toMatchObject({
      obraId: OBRA_VIVA_ID,
      syncStatus: "PENDING_SYNC",
    });
    const [evento] = await database.getAllFromIndex(
      "operational_events",
      "by-client-mutation-id",
      clientMutationId,
    );
    expect(evento).toMatchObject({
      result: "PENDING",
      syncStatus: "PENDING_SYNC",
    });
  });

  it("substitui as demais operações da cadeia com proveniência íntegra", async () => {
    const database = await getCortexDb();
    await database.put("obras", obra(OBRA_MORTA_ID));
    await database.put("obras", obra(OBRA_VIVA_ID));
    const rdoId = crypto.randomUUID();
    const { cancelamentoId } = await cancelamentoRecusado(rdoId);

    expect(await reidentificarObrasInexistentesForSync()).toBe(1);

    const original = canonical(
      await database.get("outbox_mutations", cancelamentoId),
    );
    expect(original.status).toBe("REJECTED");
    expect(original.lastSafeCode).toBe("OBRA_REIDENTIFICADA_NO_CLIENTE");
    const substitutaId =
      /^SUPERSEDED_BY:(.+)$/.exec(original.blockedReason ?? "")?.[1];
    expect(substitutaId).toBeTruthy();

    const substituta = canonical(
      await database.get("outbox_mutations", substitutaId!),
    );
    expect(substituta).toMatchObject({
      status: "PENDING",
      operacao: "CANCELAR_RDO",
      obraId: OBRA_VIVA_ID,
      baseVersion: 3,
      causationId: cancelamentoId,
      blockedReason: null,
    });
    expect(substituta.payload.obraId).toBe(OBRA_VIVA_ID);
    expect(substituta.relatedEntities).toEqual([
      { tipo: "OBRA", id: OBRA_VIVA_ID, nome: null },
    ]);

    // A substituição precisa nascer com a proveniência completa — é o que a
    // sincronização confere antes de empurrar qualquer envelope canônico.
    const [eventoDaSubstituta] = await database.getAllFromIndex(
      "operational_events",
      "by-client-mutation-id",
      substituta.clientMutationId,
    );
    await expect(
      assertCanonicalMutationEventProvenance(
        substituta,
        eventoDaSubstituta as never,
      ),
    ).resolves.toBeUndefined();

    expect(await database.get("rdos", rdoId)).toMatchObject({
      obraId: OBRA_VIVA_ID,
      syncStatus: "PENDING_SYNC",
    });
  });

  it("religa a dependência da cadeia à substituição da antecessora", async () => {
    const database = await getCortexDb();
    await database.put("obras", obra(OBRA_MORTA_ID));
    await database.put("obras", obra(OBRA_VIVA_ID));
    const rdoId = crypto.randomUUID();
    const { cancelamentoId, rdo } = await cancelamentoRecusado(rdoId);
    const restaurado: LocalRdoRecord = {
      ...rdo,
      statusRdo: "RASCUNHO",
      canceladoEm: null,
      updatedAt: "2026-08-01T12:00:00.000Z",
    };
    const restauracao = await commitLocalMutation({
      deviceId: DEVICE_ID,
      userId: USER_ID,
      obraId: OBRA_MORTA_ID,
      entityType: "RDO",
      entityId: rdoId,
      operation: "TRANSITION",
      transportOperation: "RESTAURAR_RDO",
      baseVersion: 4,
      occurredAt: "2026-08-01T12:00:00.000Z",
      previousSnapshot: {
        id: rdoId,
        obraId: OBRA_MORTA_ID,
        status: "CANCELADA",
        canceladoEm: "2026-08-01T11:00:00.000Z",
      },
      nextSnapshot: {
        id: rdoId,
        obraId: OBRA_MORTA_ID,
        status: "RASCUNHO",
        canceladoEm: null,
      },
      principalSnapshot: { ...restaurado },
      expectedPrincipalSnapshot: { ...rdo },
      eventType: "RDO_RESTAURADO",
      colaboradorId: USER_ID,
      relatedEntities: [{ tipo: "OBRA", id: OBRA_MORTA_ID }],
      causationId: cancelamentoId,
      dependsOnMutationIds: [cancelamentoId],
      write: () => [{ store: "rdos", value: restaurado, principal: true }],
    });

    expect(await reidentificarObrasInexistentesForSync()).toBe(2);

    const cancelamento = canonical(
      await database.get("outbox_mutations", cancelamentoId),
    );
    const cancelamentoSubstitutoId =
      /^SUPERSEDED_BY:(.+)$/.exec(cancelamento.blockedReason ?? "")?.[1];
    const restauracaoOriginal = canonical(
      await database.get(
        "outbox_mutations",
        restauracao.mutation.clientMutationId,
      ),
    );
    const restauracaoSubstitutaId =
      /^SUPERSEDED_BY:(.+)$/.exec(
        restauracaoOriginal.blockedReason ?? "",
      )?.[1];
    const restauracaoSubstituta = canonical(
      await database.get("outbox_mutations", restauracaoSubstitutaId!),
    );
    // Sem a religação, a substituição da restauração esperaria para sempre
    // por uma antecessora que nunca mais sobe.
    expect(restauracaoSubstituta.dependsOnMutationIds).toEqual([
      cancelamentoSubstitutoId,
    ]);
    expect(restauracaoSubstituta.obraId).toBe(OBRA_VIVA_ID);
  });

  it("drena também o trecho desenhado, que é dado de campo igual ao RDO", async () => {
    // O caso observado em produção: os quinze presos eram geometrias, não
    // RDOs. O desenho só existe neste dispositivo — quem marcou o trecho
    // estava lá — e ficar preso a uma obra morta o condenava ao mesmo limbo.
    const database = await getCortexDb();
    await database.put("obras", obra(OBRA_MORTA_ID));
    await database.put("obras", obra(OBRA_VIVA_ID));
    const geometriaId = crypto.randomUUID();
    const coordenadas = [
      [-47.4, -22.5],
      [-47.3, -22.4],
    ];
    const geometria: ObraGeometriaLocalRecord = {
      id: geometriaId,
      ownerId: USER_ID,
      obraId: OBRA_MORTA_ID,
      categoria: "TRECHO",
      objetoTipo: "TRECHO",
      objetoId: crypto.randomUUID(),
      geometry: { type: "LineString", coordinates: coordenadas },
      properties: {},
      fonte: "GESTAO_MAPA",
      status: "ATIVA",
      validoDesde: "2026-08-01T10:00:00.000Z",
      validoAte: null,
      versao: 0,
      syncStatus: "PENDING_SYNC",
      fetchedAt: null,
      updatedAt: "2026-08-01T10:00:00.000Z",
    };
    const committed = await commitLocalMutation({
      deviceId: DEVICE_ID,
      userId: USER_ID,
      obraId: OBRA_MORTA_ID,
      entityType: "GEOMETRIA_OBRA",
      entityId: geometriaId,
      entityName: "TRECHO",
      operation: "CREATE",
      transportOperation: "REGISTRAR_GEOMETRIA_OBRA",
      baseVersion: null,
      occurredAt: "2026-08-01T10:00:00.000Z",
      previousSnapshot: {},
      nextSnapshot: {
        id: geometriaId,
        obraId: OBRA_MORTA_ID,
        categoria: "TRECHO",
        objetoTipo: "TRECHO",
        objetoId: geometria.objetoId,
        geometry: geometria.geometry,
        properties: {},
        fonte: "GESTAO_MAPA",
        validoDesde: geometria.validoDesde,
        validoAte: null,
      },
      principalSnapshot: { ...geometria },
      expectedPrincipalSnapshot: null,
      eventType: "GEOMETRIA_CRIADA",
      colaboradorId: USER_ID,
      relatedEntities: [{ tipo: "OBRA", id: OBRA_MORTA_ID }],
      write: () => [
        { store: "obra_geometrias", value: geometria, principal: true },
      ],
    });
    await markMutationAsSyncing(committed.mutation);
    await rejectMutationLocally(
      committed.mutation.clientMutationId,
      "OBRA_NAO_ENCONTRADA",
      RECUSA,
    );

    expect(await reidentificarObrasInexistentesForSync()).toBe(1);

    const original = canonical(
      await database.get(
        "outbox_mutations",
        committed.mutation.clientMutationId,
      ),
    );
    const substitutaId =
      /^SUPERSEDED_BY:(.+)$/.exec(original.blockedReason ?? "")?.[1];
    const substituta = canonical(
      await database.get("outbox_mutations", substitutaId!),
    );
    expect(substituta).toMatchObject({
      status: "PENDING",
      operacao: "REGISTRAR_GEOMETRIA_OBRA",
      obraId: OBRA_VIVA_ID,
      blockedReason: null,
    });
    expect(substituta.payload.obraId).toBe(OBRA_VIVA_ID);
    // O desenho é o dado: nenhum ponto pode ser recalculado na travessia.
    expect(substituta.payload.geometry).toEqual({
      type: "LineString",
      coordinates: coordenadas,
    });

    const [eventoDaSubstituta] = await database.getAllFromIndex(
      "operational_events",
      "by-client-mutation-id",
      substituta.clientMutationId,
    );
    await expect(
      assertCanonicalMutationEventProvenance(
        substituta,
        eventoDaSubstituta as never,
      ),
    ).resolves.toBeUndefined();

    expect(await database.get("obra_geometrias", geometriaId)).toMatchObject({
      obraId: OBRA_VIVA_ID,
      syncStatus: "PENDING_SYNC",
      geometry: { type: "LineString", coordinates: coordenadas },
    });
  });

  it("não faz nada sem uma gêmea única", async () => {
    const database = await getCortexDb();
    await database.put("obras", obra(OBRA_MORTA_ID));
    const rdoId = crypto.randomUUID();
    const clientMutationId = await criacaoRecusada(rdoId);

    expect(await reidentificarObrasInexistentesForSync()).toBe(0);
    expect(
      (await database.get("outbox_mutations", clientMutationId))!.status,
    ).toBe("REJECTED");

    // Duas candidatas com a mesma identidade: ambígua, ninguém decide por
    // quem opera.
    await database.put("obras", obra(OBRA_VIVA_ID));
    await database.put(
      "obras",
      obra("00000000-0000-4000-8000-0000000000cc"),
    );
    expect(await reidentificarObrasInexistentesForSync()).toBe(0);
    expect(
      (await database.get("outbox_mutations", clientMutationId))!.status,
    ).toBe("REJECTED");
  });

  it("não quica de volta quando a gêmea também não existe no servidor", async () => {
    const database = await getCortexDb();
    await database.put("obras", obra(OBRA_MORTA_ID));
    await database.put("obras", obra(OBRA_VIVA_ID));
    const rdoId = crypto.randomUUID();
    const { cancelamentoId } = await cancelamentoRecusado(rdoId);

    expect(await reidentificarObrasInexistentesForSync()).toBe(1);
    const original = canonical(
      await database.get("outbox_mutations", cancelamentoId),
    );
    const substitutaId =
      /^SUPERSEDED_BY:(.+)$/.exec(original.blockedReason ?? "")?.[1];
    const substituta = canonical(
      await database.get("outbox_mutations", substitutaId!),
    );

    // O servidor recusa a substituição do mesmo jeito: a gêmea também morreu.
    await markMutationAsSyncing(substituta);
    await rejectMutationLocally(
      substituta.clientMutationId,
      "OBRA_NAO_ENCONTRADA",
      RECUSA,
    );

    expect(await reidentificarObrasInexistentesForSync()).toBe(0);
    expect(
      (await database.get(
        "outbox_mutations",
        substituta.clientMutationId,
      ))!.status,
    ).toBe("REJECTED");
  });
});
