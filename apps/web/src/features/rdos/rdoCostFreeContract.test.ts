import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

import type { LocalRdoRecord } from "../../lib/db/db.types";
import { buildRdoSyncPayload } from "../../lib/db/localRdoService";
import {
  createEmptyAlocacaoColaborador,
  createEmptyRdo,
  createEmptyServicoExecutado,
} from "./createEmptyRdo";
import { localRecordToDraft } from "./localRecordToDraft";

const COST_RUNTIME_PATTERN =
  /\bcusto(?:Realizado|Hora|Total)\b|Custo\s*(?:realizado|\/hora)/i;

function source(relativePath: string): string {
  return readFileSync(new URL(relativePath, import.meta.url), "utf8");
}

function legacyRecord(): LocalRdoRecord {
  return {
    id: "00000000-0000-4000-8000-000000000030",
    obraId: "00000000-0000-4000-8000-000000000001",
    programacaoId: null,
    numeroRdo: "RDO-LEGACY-1",
    dataRdo: "2026-07-20",
    statusRdo: "RASCUNHO",
    syncStatus: "SYNCED",
    versaoEntidade: 3,
    payload: {
      servicosExecutados: [{
        localId: "00000000-0000-4000-8000-000000000031",
        servicoNome: "Pavimentação",
        quantidadeExecutada: "12",
        custoRealizado: "9999.99",
      }],
      alocacoesColaboradores: [{
        localId: "00000000-0000-4000-8000-000000000032",
        colaboradorId: "00000000-0000-4000-8000-000000000033",
        custoHora: "123.45",
        custoTotal: "987.60",
      }],
    },
    createdAt: "2026-07-20T12:00:00.000Z",
    updatedAt: "2026-07-20T12:00:00.000Z",
  };
}

describe("contrato RDO Cortex 3 sem custo subjetivo", () => {
  it("não mantém campos ou labels de custo no runtime do editor", () => {
    const runtime = [
      "./rdo.types.ts",
      "./createEmptyRdo.ts",
      "./localRecordToDraft.ts",
      "./RdoCreatePage.tsx",
      "../../lib/db/localRdoService.ts",
    ].map(source).join("\n");

    expect(runtime).not.toMatch(COST_RUNTIME_PATTERN);
  });

  it("descarta custo de registro local histórico antes de novo payload sync", () => {
    const draft = localRecordToDraft(legacyRecord());
    const payload = buildRdoSyncPayload(draft);

    expect(JSON.stringify({ draft, payload })).not.toMatch(COST_RUNTIME_PATTERN);
  });

  it("sanitiza chaves extras de custo em vez de espalhá-las no payload", () => {
    const draft = createEmptyRdo();
    const legacyService = Object.assign(createEmptyServicoExecutado(), {
      servicoNome: "Pavimentação",
      quantidadeExecutada: 8,
      custoRealizado: "800.00",
    });
    const legacyAllocation = Object.assign(
      createEmptyAlocacaoColaborador(),
      {
      colaboradorId: "00000000-0000-4000-8000-000000000033",
      custoHora: "50.00",
      custoTotal: "400.00",
      },
    );
    draft.servicosExecutados = [legacyService];
    draft.alocacoesColaboradores = [legacyAllocation];

    expect(JSON.stringify(buildRdoSyncPayload(draft)))
      .not.toMatch(COST_RUNTIME_PATTERN);
  });
});
