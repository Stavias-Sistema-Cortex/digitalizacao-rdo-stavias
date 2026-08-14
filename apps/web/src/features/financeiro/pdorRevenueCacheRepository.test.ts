// @vitest-environment jsdom

import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiTransportError } from "../../lib/api/apiClient";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import type { LocalRdoRecord } from "../../lib/db/db.types";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import { updateSyncState } from "../../lib/db/syncStateRepository";
import {
  clearSession,
  setOfflineSession,
  setSession,
} from "../auth/authSession";
import type { ObraPdor } from "../obras/obrasApi";
import { queueCancelRdo } from "../rdos/rdoLifecycle";
import {
  queueCreatePrice,
  queueCreateService,
} from "./servicePriceRepository";
import {
  loadPdorRevenueSnapshot,
  PDOR_REVENUE_CACHE_MAX_AGE_MS,
} from "./pdorRevenueCacheRepository";

const OWNER_A = "00000000-0000-4000-8000-000000000201";
const OWNER_B = "00000000-0000-4000-8000-000000000202";
const WORKSITE_A = "00000000-0000-4000-8000-000000000101";
const WORKSITE_B = "00000000-0000-4000-8000-000000000102";
const SNAPSHOT_ID = "00000000-0000-4000-8000-000000000301";
const EVIDENCE_ID = "00000000-0000-4000-8000-000000000401";
const RDO_ID = "00000000-0000-4000-8000-000000000501";
const DEVICE_ID = "00000000-0000-4000-8000-000000000601";
const FETCHED_AT_MS = Date.parse("2026-07-23T15:00:00.000Z");
const databases = new Set<string>();

const PDOR: ObraPdor = {
  id: SNAPSHOT_ID,
  obraId: WORKSITE_A,
  dataReferencia: "2026-07-22",
  janelaTemporal: {
    inicioProgramacao: "2026-01-05",
    fimProgramacao: "2026-07-22",
    dataReferencia: "2026-07-22",
    janelaEquipamentosDias: 30,
    serieHistoricaSemanal: true,
  },
  dataExecucao: "2026-07-22T14:59:58",
  versaoModelo: "PDOR-0.5.1",
  versaoPremissas: "PDOR-ASSUMPTIONS-0.5.0",
  versaoDados: "commit:812",
  statusExecucao: "SUCCESS",
  statusExecucaoLabel: "Concluído",
  calibracao: "CALIBRATED",
  calibracaoLabel: "Calibrado",
  risco: "MODERATE",
  riscoLabel: "Moderado",
  faseLabel: "Produção",
  receitaPrevistaFinal: 934000.1,
  p10: 880000.5,
  p50: 912345.67,
  p80: 940000,
  p95: 955000,
  probabilidadeAbaixoContrato: 0.42,
  confianca: 0.61,
  drivers: [],
  warnings: [],
  featuresUtilizadas: [],
  dadosAusentes: [],
  limitacoes: [],
  alertas: [],
  recomendacoes: [],
  comparacaoAnterior: null,
  evidencias: [
    {
      entityType: "RDO",
      entityId: RDO_ID,
      source: "rdo",
      role: "EXECUCAO_REAL",
      observedAt: "2026-07-22T14:58:00Z",
    },
    {
      entityType: "REVENUE_EVIDENCE",
      entityId: EVIDENCE_ID,
      source: "RDO",
      role: "ACCEPTED_EXACT",
      observedAt: "2026-07-22T14:58:00Z",
    },
  ],
  iniciadoPor: OWNER_A,
  tipoIniciador: "PROCESS",
  algorithmVersion: "PDOR-REVENUE-2",
  evidenceIds: [EVIDENCE_ID],
  evidenceHighWaterMark: 812,
  coverageCode: "COMPLETE_ACCEPTED_EXACT",
  assumptions: { currency: "BRL" },
  executedAtUtc: "2026-07-22T14:59:58Z",
  stale: false,
  current: true,
  erroExecucao: null,
};

function profile(
  ownerId: string,
  obraIds: string[] = [WORKSITE_A, WORKSITE_B],
) {
  return {
    colaboradorId: ownerId,
    nome: "Responsável financeiro",
    papelAcesso: "BETA" as const,
    escopoGlobal: false,
    obraIds,
    expiraEm: "2099-07-23T15:00:00.000Z",
  };
}

async function trackDatabase(ownerId: string, obraIds: string[]) {
  databases.add(
    await databaseNameForScope(
      ownerId,
      `BETA:${[...obraIds].sort().join(",")}`,
    ),
  );
}

function request(obraId = WORKSITE_A) {
  return { obraId };
}

function rdo(
  partial: Partial<LocalRdoRecord> = {},
): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: WORKSITE_A,
    programacaoId: null,
    numeroRdo: "RDO-0051",
    dataRdo: "2026-07-22",
    statusRdo: "ENVIADO",
    syncStatus: "SYNCED",
    versaoEntidade: 7,
    payload: {
      obraId: WORKSITE_A,
      numeroRdo: "RDO-0051",
    },
    createdAt: "2026-07-22T14:00:00.000Z",
    updatedAt: "2026-07-22T14:00:00.000Z",
    canceladoEm: null,
    ...partial,
  };
}

function canonicalJsonForFixture(value: unknown): string {
  if (value === null) return "null";
  if (typeof value === "string" || typeof value === "boolean" ||
    typeof value === "number") {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJsonForFixture).join(",")}]`;
  }
  if (typeof value === "object") {
    const record = value as Record<string, unknown>;
    return `{${Object.keys(record)
      .filter((key) => record[key] !== undefined)
      .sort()
      .map((key) =>
        `${JSON.stringify(key)}:${canonicalJsonForFixture(record[key])}`
      )
      .join(",")}}`;
  }
  throw new Error("fixture não serializável");
}

async function hashFixture(value: unknown): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(canonicalJsonForFixture(value)),
  ));
  return Array.from(digest, (byte) =>
    byte.toString(16).padStart(2, "0")).join("");
}

beforeEach(async () => {
  vi.stubGlobal("BroadcastChannel", undefined);
  setSession(profile(OWNER_A));
  await trackDatabase(OWNER_A, [WORKSITE_A, WORKSITE_B]);
  await updateSyncState({
    deviceId: DEVICE_ID,
    usuarioId: OWNER_A,
    lastPulledCommitSeq: 812,
    lastAckedCommitSeq: 812,
  });
});

afterEach(async () => {
  await closeCortexDb();
  clearSession();
  for (const databaseName of databases) {
    await deleteDB(databaseName);
  }
  databases.clear();
  vi.unstubAllGlobals();
});

describe("PDOR revenue cache", () => {
  it("reutiliza offline somente o snapshot confirmado do mesmo usuário, escopo e obra", async () => {
    const online = await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => PDOR,
    });

    expect(online).toMatchObject({
      pdor: PDOR,
      mode: "ONLINE",
      source: "SERVER_CONFIRMED",
      provenance: {
        snapshotId: SNAPSHOT_ID,
        worksiteId: WORKSITE_A,
        referenceDate: "2026-07-22",
        temporalWindow: PDOR.janelaTemporal,
        evidenceHighWaterMark: 812,
        coverageCode: "COMPLETE_ACCEPTED_EXACT",
        evidenceCount: 1,
      },
    });
    const database = await getCortexDb();
    const [cached] = await database.getAll("finance_pdor_revenue_cache");
    expect(cached?.key).toEqual([
      OWNER_A,
      `BETA:${WORKSITE_A},${WORKSITE_B}`,
      WORKSITE_A,
    ]);
    expect(cached).not.toHaveProperty("fromFilter");
    expect(cached).not.toHaveProperty("toFilter");

    setOfflineSession(profile(OWNER_A));
    const offline = await loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + 1,
      fetchCurrent: async () => {
        throw new Error("não deveria consultar o servidor offline");
      },
    });

    expect(offline).toMatchObject({
      pdor: PDOR,
      mode: "OFFLINE_CACHE",
      source: "SERVER_CONFIRMED",
      provenance: online.provenance,
    });
  });

  it("não reutiliza SUCCESS quando o RDO que o sustenta tem cancelamento local pendente", async () => {
    await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => PDOR,
    });
    const database = await getCortexDb();
    await database.put("rdos", rdo());
    await queueCancelRdo(rdo());

    await expect(loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS + 1,
      fetchCurrent: async () => PDOR,
    })).rejects.toThrow(/RDO.*cancelamento.*pendente/i);

    setOfflineSession(profile(OWNER_A));

    await expect(loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + 2,
    })).rejects.toThrow(/RDO.*cancelamento.*pendente/i);
  });

  it("não ressuscita o cache depois que o cancelamento do RDO já sincronizou", async () => {
    await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => PDOR,
    });
    const database = await getCortexDb();
    await database.put("rdos", {
      ...rdo(),
      canceladoEm: "2026-07-23T15:00:00.000Z",
    });
    setOfflineSession(profile(OWNER_A));

    await expect(loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + 1,
    })).rejects.toThrow(/RDO.*cancelado/i);
  });

  it("bloqueia cancelamento de RDO elegível mesmo fora da lista truncada de evidências", async () => {
    await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => PDOR,
    });
    const database = await getCortexDb();
    const olderRdo = rdo({
      id: "00000000-0000-4000-8000-000000000502",
      numeroRdo: "RDO-0001",
      dataRdo: "2026-01-05",
    });
    await database.put("rdos", olderRdo);
    await queueCancelRdo(olderRdo);
    setOfflineSession(profile(OWNER_A));

    await expect(loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + 1,
    })).rejects.toThrow(/RDO.*cancelamento.*pendente/i);
  });

  it.each([
    "CRIAR_RDO",
    "ATUALIZAR_RDO_RASCUNHO",
    "ENVIAR_RDO",
    "RESTAURAR_RDO",
  ] as const)("bloqueia cache diante de %s local na janela", async (operacao) => {
    await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => PDOR,
    });
    const database = await getCortexDb();
    await database.put("outbox_mutations", {
      clientMutationId: `mutation-${operacao.toLowerCase()}`,
      entidadeTipo: "RDO",
      entidadeId: RDO_ID,
      operacao,
      baseVersao: 7,
      payload: {
        id: RDO_ID,
        obraId: WORKSITE_A,
        dataRdo: "2026-07-22",
      },
      status: "PENDING",
      tentativas: 0,
      ultimaTentativaEm: null,
      ultimoErro: null,
      conflito: null,
      criadaNoClienteEm: "2026-07-23T15:00:00.001Z",
      updatedAt: "2026-07-23T15:00:00.001Z",
    });
    setOfflineSession(profile(OWNER_A));

    await expect(loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + 2,
    })).rejects.toThrow(/RDO.*posterior.*pendente/i);
  });

  it("aceita snapshot confirmado estritamente depois de cancelamento já sincronizado", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdo({
      canceladoEm: "2026-07-23T14:59:59.999Z",
    }));

    const online = await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => PDOR,
    });

    expect(online.pdor).toEqual(PDOR);
  });

  it("bloqueia cache diante de cadastro de serviço pendente da mesma obra", async () => {
    await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => PDOR,
    });
    await queueCreateService(WORKSITE_A, {
      code: "SERV-PENDENTE",
      name: "Serviço ainda local",
    });
    setOfflineSession(profile(OWNER_A));

    await expect(loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + 1,
    })).rejects.toThrow(/catálogo.*pendente/i);
  });

  it("trata alteração de catálogo como global no escopo, não só na obra iniciadora", async () => {
    await loadPdorRevenueSnapshot(request(WORKSITE_B), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => ({ ...PDOR, obraId: WORKSITE_B }),
    });
    await queueCreateService(WORKSITE_A, {
      code: "SERV-GLOBAL",
      name: "Serviço global ainda local",
    });
    setOfflineSession(profile(OWNER_A));

    await expect(loadPdorRevenueSnapshot(request(WORKSITE_B), {
      online: false,
      now: FETCHED_AT_MS + 1,
    })).rejects.toThrow(/catálogo.*pendente/i);
  });

  it("bloqueia cache diante de preço pendente da mesma obra", async () => {
    await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => PDOR,
    });
    const database = await getCortexDb();
    const service = await queueCreateService(WORKSITE_A, {
      code: "SERV-PRECO",
      name: "Serviço com preço local",
    });
    const serviceMutation = await database.get(
      "outbox_mutations",
      service.clientMutationId,
    );
    await database.put("outbox_mutations", {
      ...serviceMutation!,
      status: "SYNCED",
      criadaNoClienteEm: "2026-07-23T14:00:00.000Z",
      updatedAt: "2026-07-23T14:30:00.000Z",
    });
    await queueCreatePrice(WORKSITE_A, service.entityId, {
      unit: "m",
      currency: "BRL",
      unitPrice: "123,45",
      contractedQuantity: "10",
      validFrom: "2026-01-01",
      source: "CONTRATO",
    });
    setOfflineSession(profile(OWNER_A));

    await expect(loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + 1,
    })).rejects.toThrow(/preço.*pendente/i);
  });

  it("mantém preço sincronizado posterior ao fetchedAt bloqueando o cache antigo", async () => {
    await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => PDOR,
    });
    const database = await getCortexDb();
    const service = await queueCreateService(WORKSITE_A, {
      code: "SERV-PRECO-SYNC",
      name: "Serviço confirmado antes do preço",
    });
    const serviceMutation = await database.get(
      "outbox_mutations",
      service.clientMutationId,
    );
    await database.put("outbox_mutations", {
      ...serviceMutation!,
      status: "SYNCED",
      criadaNoClienteEm: "2026-07-23T14:00:00.000Z",
      updatedAt: "2026-07-23T14:30:00.000Z",
    });
    const price = await queueCreatePrice(WORKSITE_A, service.entityId, {
      unit: "m",
      currency: "BRL",
      unitPrice: "200,00",
      contractedQuantity: "10",
      validFrom: "2026-01-01",
      source: "CONTRATO",
    });
    const priceMutation = await database.get(
      "outbox_mutations",
      price.clientMutationId,
    );
    await database.put("outbox_mutations", {
      ...priceMutation!,
      status: "SYNCED",
      criadaNoClienteEm: "2026-07-23T15:00:00.001Z",
      updatedAt: "2026-07-23T15:00:00.002Z",
    });
    setOfflineSession(profile(OWNER_A));

    await expect(loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + 3,
    })).rejects.toThrow(/preço.*confirmação/i);
  });

  it.each(["ARQUIVAR_OBRA", "RESTAURAR_OBRA"] as const)(
    "não exibe cache durante %s local da obra",
    async (operacao) => {
      await loadPdorRevenueSnapshot(request(), {
        online: true,
        now: FETCHED_AT_MS,
        fetchCurrent: async () => PDOR,
      });
      const database = await getCortexDb();
      await database.put("outbox_mutations", {
        clientMutationId: `mutation-${operacao.toLowerCase()}`,
        entidadeTipo: "OBRA",
        entidadeId: WORKSITE_A,
        operacao,
        baseVersao: 7,
        payload: { id: WORKSITE_A, obraId: WORKSITE_A },
        status: "PENDING",
        tentativas: 0,
        ultimaTentativaEm: null,
        ultimoErro: null,
        conflito: null,
        criadaNoClienteEm: "2026-07-23T15:00:00.001Z",
        updatedAt: "2026-07-23T15:00:00.001Z",
      });
      setOfflineSession(profile(OWNER_A));

      await expect(loadPdorRevenueSnapshot(request(), {
        online: false,
        now: FETCHED_AT_MS + 2,
      })).rejects.toThrow(/arquivamento.*restauração/i);
    },
  );

  it.each([
    ["algoritmo v1", { algorithmVersion: "PDOR-REVENUE-1" }],
    ["modelo anterior", { versaoModelo: "PDOR-0.5.0" }],
    ["premissas anteriores", { versaoPremissas: "PDOR-ASSUMPTIONS-0.4.0" }],
  ])("rejeita cache offline incompatível: %s", async (_label, legacy) => {
    await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => PDOR,
    });
    const database = await getCortexDb();
    const [cached] = await database.getAll("finance_pdor_revenue_cache");
    const response = { ...(cached!.response as ObraPdor), ...legacy };
    await database.put("finance_pdor_revenue_cache", {
      ...cached!,
      response,
      payloadHash: await hashFixture(response),
      provenance: {
        ...cached!.provenance,
        algorithmVersion: response.algorithmVersion,
      },
    });
    setOfflineSession(profile(OWNER_A));

    await expect(loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + 1,
    })).rejects.toThrow(/nenhuma previsão PDOR confirmada/i);
  });

  it("falha fechada sem cache confirmado para a chave exata", async () => {
    setOfflineSession(profile(OWNER_A));

    await expect(loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS,
    })).rejects.toThrow(
      /nenhuma previsão PDOR confirmada.*usuário e obra/i,
    );
  });

  it("não cruza snapshots entre usuário, escopo ou obra", async () => {
    await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => PDOR,
    });

    setOfflineSession(profile(OWNER_B));
    await trackDatabase(OWNER_B, [WORKSITE_A, WORKSITE_B]);
    await expect(loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + 1,
    })).rejects.toThrow(/nenhuma previsão PDOR confirmada/i);

    setOfflineSession(profile(OWNER_A, [WORKSITE_A]));
    await trackDatabase(OWNER_A, [WORKSITE_A]);
    await expect(loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + 1,
    })).rejects.toThrow(/nenhuma previsão PDOR confirmada/i);

    setOfflineSession(profile(OWNER_A));
    await expect(loadPdorRevenueSnapshot(request(WORKSITE_B), {
      online: false,
      now: FETCHED_AT_MS + 1,
    })).rejects.toThrow(/nenhuma previsão PDOR confirmada/i);
  });

  it("falha fechada quando o snapshot expirou", async () => {
    await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => PDOR,
    });
    setOfflineSession(profile(OWNER_A));

    await expect(loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + PDOR_REVENUE_CACHE_MAX_AGE_MS + 1,
    })).rejects.toThrow(/expirou/i);
  });

  it("rejeita cache adulterado antes de expor o PDOR", async () => {
    await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => PDOR,
    });
    const database = await getCortexDb();
    const [cached] = await database.getAll("finance_pdor_revenue_cache");
    expect(cached).toBeDefined();
    await database.put("finance_pdor_revenue_cache", {
      ...cached,
      response: {
        ...(cached.response as ObraPdor),
        receitaPrevistaFinal: 1,
      },
    });
    setOfflineSession(profile(OWNER_A));

    await expect(loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + 1,
    })).rejects.toThrow(/nenhuma previsão PDOR confirmada/i);
  });

  it("rejeita resposta online fora da obra ou sem proveniência atual", async () => {
    await expect(loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => ({ ...PDOR, obraId: WORKSITE_B }),
    })).rejects.toThrow(/obra financeira solicitada/i);

    await expect(loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => ({ ...PDOR, current: false, stale: true }),
    })).rejects.toThrow(/snapshot PDOR atual/i);

    await expect(loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => ({
        ...PDOR,
        janelaTemporal: {
          ...PDOR.janelaTemporal!,
          dataReferencia: "2026-07-21",
        },
      }),
    })).rejects.toThrow(/janela temporal.*divergente/i);

    const database = await getCortexDb();
    expect(await database.count("finance_pdor_revenue_cache")).toBe(0);
  });

  it("rejeita SUCCESS sem evidência aceita e não guarda receita fantasma", async () => {
    await expect(loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => ({
        ...PDOR,
        coverageCode: "NO_ACCEPTED_EVIDENCE",
        evidenceIds: [],
        evidencias: [],
      }),
    })).rejects.toThrow(/sem evidência de receita aceita/i);

    const database = await getCortexDb();
    expect(await database.count("finance_pdor_revenue_cache")).toBe(0);
  });

  it("preserva a ausência confirmada pelo servidor sem confundi-la com falta de cache", async () => {
    const online = await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => null,
    });
    expect(online.pdor).toBeNull();
    expect(online.provenance.coverageCode).toBe("NO_CURRENT_SNAPSHOT");

    setOfflineSession(profile(OWNER_A));
    const offline = await loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + 1,
    });
    expect(offline.pdor).toBeNull();
    expect(offline.mode).toBe("OFFLINE_CACHE");
    expect(offline.provenance.coverageCode).toBe("NO_CURRENT_SNAPSHOT");
  });

  /*
   * Apagar o RDO que sustentava a projeção deixa a obra sem entrada
   * suficiente, e é esse snapshot que passa a ser o atual — é assim que o
   * número calculado sobre o RDO morto sai da tela.
   *
   * A verificação exigia SUCCESS junto com "é o atual", e as duas coisas
   * andavam juntas só porque o snapshot de dados insuficientes nunca era o
   * atual. Deixando a exigência de pé, o servidor passaria a responder
   * corretamente "faltam dados" e a tela devolveria um erro, escondendo tanto o
   * motivo quanto a lista do que falta preencher.
   */
  it("aceita o snapshot de dados insuficientes quando ele é o atual", async () => {
    const semDados: ObraPdor = {
      ...PDOR,
      statusExecucao: "INSUFFICIENT_DATA",
      statusExecucaoLabel: "Dados insuficientes",
      erroExecucao: "Dados insuficientes para calcular o PDOR.",
      receitaPrevistaFinal: null,
      p10: null,
      p50: null,
      p80: null,
      p95: null,
      probabilidadeAbaixoContrato: null,
      confianca: null,
      evidencias: [],
      evidenceIds: [],
      evidenceHighWaterMark: null,
      dadosAusentes: [{
        code: "",
        label: "Consumo real de material",
        detail: null,
        field: "materialConsumption",
        availability: "ABSENT",
      }],
    };

    const online = await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => semDados,
    });

    expect(online.pdor?.statusExecucao).toBe("INSUFFICIENT_DATA");
    expect(online.mode).toBe("ONLINE");

    // E ele fica no cache, para a mesma resposta valer offline.
    setOfflineSession(profile(OWNER_A));
    const offline = await loadPdorRevenueSnapshot(request(), {
      online: false,
      now: FETCHED_AT_MS + 1,
    });
    expect(offline.pdor?.statusExecucao).toBe("INSUFFICIENT_DATA");
    expect(offline.mode).toBe("OFFLINE_CACHE");
  });

  /*
   * O que continua valendo: um snapshot vencido não é resposta. Dele sairia
   * exatamente o número velho que esta tela precisa parar de mostrar.
   */
  it("recusa o snapshot de dados insuficientes que já não é o atual", async () => {
    await expect(loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => ({
        ...PDOR,
        statusExecucao: "INSUFFICIENT_DATA",
        current: false,
        stale: true,
      }),
    })).rejects.toThrow(/snapshot PDOR atual/i);
  });

  it("usa o cache somente em falha real de transporte", async () => {
    await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: async () => PDOR,
    });

    const fallback = await loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS + 1,
      fetchCurrent: async () => {
        throw new ApiTransportError("sem rede", "CONNECTION");
      },
    });
    expect(fallback.mode).toBe("OFFLINE_CACHE");

    await expect(loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS + 1,
      fetchCurrent: async () => {
        throw new Error("resposta inválida");
      },
    })).rejects.toThrow("resposta inválida");
  });

  it("descarta uma resposta remota se a sessão mudar durante a consulta", async () => {
    let resolveRemote: ((value: ObraPdor) => void) | undefined;
    let signalFetchStarted: (() => void) | undefined;
    const fetchStarted = new Promise<void>((resolve) => {
      signalFetchStarted = resolve;
    });
    const remote = new Promise<ObraPdor>((resolve) => {
      resolveRemote = resolve;
    });
    const pending = loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: () => {
        signalFetchStarted?.();
        return remote;
      },
    });
    const rejection = expect(pending).rejects.toThrow(/sessão mudou/i);

    await fetchStarted;
    setSession(profile(OWNER_B));
    await trackDatabase(OWNER_B, [WORKSITE_A, WORKSITE_B]);
    resolveRemote?.(PDOR);

    await rejection;
    const database = await getCortexDb();
    expect(await database.count("finance_pdor_revenue_cache")).toBe(0);
  });
});
