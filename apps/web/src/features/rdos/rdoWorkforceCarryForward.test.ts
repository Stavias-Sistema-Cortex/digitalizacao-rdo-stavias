import { describe, expect, it } from "vitest";

import {
  addAuthorizedWorkforceMember,
  carryForwardWorkforce,
} from "./rdoWorkforceCarryForward";
import type {
  RdoContextCollaborator,
  RdoPreviousWorkforceItem,
} from "./rdoLookupApi";

const source = "00000000-0000-4000-8000-000000000099";
const catalog: RdoContextCollaborator[] = [
  { id: "worker-a", codigoColaborador: null, nome: "Ana", papelNaObra: "APONTADOR", nomePerfil: "Apontadora" },
  { id: "worker-b", codigoColaborador: null, nome: "Bruno", papelNaObra: "OPERACIONAL", nomePerfil: "Operador" },
];

function previous(
  collaboratorId: string | null,
  overrides: Partial<RdoPreviousWorkforceItem> = {},
): RdoPreviousWorkforceItem {
  return {
    sourceItemId: `item-${collaboratorId ?? "unknown"}`,
    sourceRdoId: source,
    collaboratorId,
    nameSnapshot: collaboratorId ?? "Identidade histórica indisponível",
    roleSnapshot: "Operador",
    linkType: "PROPRIO",
    quantity: 1,
    startTime: "07:00:00",
    endTime: "17:00:00",
    observations: null,
    availability: "AVAILABLE",
    ...overrides,
  };
}

describe("carry-forward determinístico da equipe", () => {
  it("seleciona todos os trabalhadores anteriores ainda autorizados", () => {
    const rows = carryForwardWorkforce(
      [previous("worker-a"), previous("worker-b")],
      catalog,
      (() => {
        const ids = ["new-a", "new-b"];
        return () => ids.shift()!;
      })(),
    );

    expect(rows.filter((row) => row.selected).map((row) => row.colaboradorId))
      .toEqual(["worker-a", "worker-b"]);
    expect(rows.map((row) => row.localId)).toEqual(["new-a", "new-b"]);
  });

  it("mantém proveniência indisponível, mas desmarca a pessoa", () => {
    const [row] = carryForwardWorkforce([previous("historical")], []);

    expect(row).toMatchObject({
      colaboradorId: "historical",
      sourceRdoId: source,
      origemItemId: "item-historical",
      selected: false,
      availability: "UNAVAILABLE",
    });
  });

  it("retém evidência histórica com collaboratorId nulo sem crash ou seleção", () => {
    const [row] = carryForwardWorkforce(
      [
        previous(null, {
          sourceItemId: "legacy-item-without-identity",
          nameSnapshot: "Trabalhador do RDO legado",
        }),
      ],
      catalog,
      () => "retained-null-evidence",
    );

    expect(row).toMatchObject({
      localId: "retained-null-evidence",
      origemItemId: "legacy-item-without-identity",
      sourceRdoId: source,
      colaboradorId: "",
      nomeColaborador: "Trabalhador do RDO legado",
      selected: false,
      availability: "UNAVAILABLE",
    });
  });

  it("deduplica pela identidade collaboratorId, nunca pelo nome", () => {
    const rows = carryForwardWorkforce(
      [previous("worker-a"), previous("worker-a", { sourceItemId: "duplicate" })],
      catalog,
    );
    expect(rows).toHaveLength(1);
  });

  it("adiciona somente colaborador autorizado e rejeita duplicidade", () => {
    const rows = addAuthorizedWorkforceMember([], "worker-a", catalog, () => "added");
    expect(rows[0]).toMatchObject({
      localId: "added",
      colaboradorId: "worker-a",
      selected: true,
      origin: "AUTHORIZED_CONTEXT",
    });
    expect(() => addAuthorizedWorkforceMember(rows, "worker-a", catalog))
      .toThrow("Este colaborador já está na equipe do RDO.");
    expect(() => addAuthorizedWorkforceMember([], "foreign", catalog))
      .toThrow("Colaborador não autorizado para esta obra.");
  });
});
