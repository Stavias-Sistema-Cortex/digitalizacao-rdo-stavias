// @vitest-environment jsdom

import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiTransportError } from "../../lib/api/apiClient";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import {
  clearSession,
  setOfflineSession,
  setSession,
} from "../auth/authSession";
import type { ObraPdor } from "../obras/obrasApi";
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
  versaoModelo: "PDOR-REVENUE-1",
  versaoPremissas: "PDOR-ASSUMPTIONS-1",
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
  evidencias: [{
    entityType: "REVENUE_EVIDENCE",
    entityId: EVIDENCE_ID,
    source: "RDO",
    role: "ACCEPTED_EXACT",
    observedAt: "2026-07-22T14:58:00Z",
  }],
  iniciadoPor: OWNER_A,
  tipoIniciador: "PROCESS",
  algorithmVersion: "PDOR-REVENUE-1",
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

beforeEach(async () => {
  vi.stubGlobal("BroadcastChannel", undefined);
  setSession(profile(OWNER_A));
  await trackDatabase(OWNER_A, [WORKSITE_A, WORKSITE_B]);
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
    const remote = new Promise<ObraPdor>((resolve) => {
      resolveRemote = resolve;
    });
    const pending = loadPdorRevenueSnapshot(request(), {
      online: true,
      now: FETCHED_AT_MS,
      fetchCurrent: () => remote,
    });

    setSession(profile(OWNER_B));
    await trackDatabase(OWNER_B, [WORKSITE_A, WORKSITE_B]);
    resolveRemote?.(PDOR);

    await expect(pending).rejects.toThrow(/sessão mudou/i);
    const database = await getCortexDb();
    expect(await database.count("finance_pdor_revenue_cache")).toBe(0);
  });
});
