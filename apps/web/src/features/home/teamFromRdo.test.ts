import { describe, expect, it } from "vitest";

import type { LocalRdoRecord } from "../../lib/db/db.types";
import { teamFromRdo } from "./teamFromRdo";

function rdoWithMaoObra(
  maoObra: Record<string, unknown>[],
): LocalRdoRecord {
  return {
    id: "rdo-1",
    obraId: "obra-1",
    programacaoId: null,
    numeroRdo: "RDO-001",
    dataRdo: "2026-07-05",
    statusRdo: "ENVIADO",
    syncStatus: "SYNCED",
    versaoEntidade: 1,
    payload: { maoObra },
    createdAt: "2026-07-05T08:00:00.000Z",
    updatedAt: "2026-07-05T08:00:00.000Z",
  };
}

describe("teamFromRdo", () => {
  it("agrupa por cargo somando quantidades (número ou string)", () => {
    const team = teamFromRdo(
      rdoWithMaoObra([
        { cargo: "Servente", quantidade: 4 },
        { cargo: "Servente", quantidade: "2" },
        { cargo: "Operador", quantidade: 3 },
        { cargo: "", quantidade: 9 },
      ]),
    );

    expect(team).toEqual([
      { cargo: "Servente", quantidade: 6 },
      { cargo: "Operador", quantidade: 3 },
    ]);
  });

  it("retorna vazio sem RDO ou sem maoObra", () => {
    expect(teamFromRdo(null)).toEqual([]);
    expect(teamFromRdo(rdoWithMaoObra([]))).toEqual([]);
  });
});
