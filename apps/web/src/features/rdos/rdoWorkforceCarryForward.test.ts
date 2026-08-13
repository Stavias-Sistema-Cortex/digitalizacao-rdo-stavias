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
  { id: "worker-a", codigoColaborador: null, nome: "Ana", papelNaObra: "APONTADOR", nomePerfil: "Apontadora", funcao: "TOPÓGRAFA" },
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
  /*
   * O retrato do RDO anterior ganhava do cadastro, e o efeito se acumulava:
   * como cada RDO herda do anterior, um nome corrigido no Academy atravessava
   * a cadeia inteira sem nunca alcançar a frente de serviço.
   */
  it("traz o nome de hoje para quem continua na obra", () => {
    const rows = carryForwardWorkforce(
      [previous("worker-a", { nameSnapshot: "ANA GRAFADA ERRADO" })],
      [{ ...catalog[0], nome: "ANA CAROLINA SOUZA" }],
      () => "linha-1",
    );

    expect(rows[0].nomeColaborador).toBe("ANA CAROLINA SOUZA");
  });

  /*
   * A equipe é uma das duas portas de autorização da obra, e apagá-la fecha
   * essa porta. A linha cinza e desmarcada reaparecia todo dia, e não havia
   * como tirá-la a não ser uma a uma — a equipe tinha sido apagada e os
   * membros seguiam escritos no RDO seguinte, e no seguinte.
   *
   * O RDO de ontem continua guardando quem trabalhou ontem. O que para é a
   * repetição para a frente.
   */
  it("não repete para a frente quem perdeu a autorização", () => {
    const rows = carryForwardWorkforce(
      [
        previous("worker-a"),
        previous("worker-sumido", {
          nameSnapshot: "JOSE QUE SAIU",
          availability: "UNAVAILABLE",
        }),
      ],
      catalog,
      (() => {
        const ids = ["linha-1", "linha-2"];
        return () => ids.shift() ?? "linha-extra";
      })(),
    );

    expect(rows.map((row) => row.colaboradorId)).toEqual(["worker-a"]);
  });

  it("também não repete quem simplesmente saiu do catálogo da obra", () => {
    const rows = carryForwardWorkforce(
      [previous("worker-sumido", { nameSnapshot: "JOSE QUE SAIU" })],
      catalog,
      () => "linha-1",
    );

    expect(rows).toEqual([]);
  });

  /*
   * A função é diferente do nome: é o que foi apontado naquele dia, e o
   * apontador pode tê-la ajustado de propósito. Sobrescrever com o perfil do
   * cadastro desfaria a escolha dele.
   */
  it("preserva a função apontada, que não é o perfil do cadastro", () => {
    const rows = carryForwardWorkforce(
      [previous("worker-a", { roleSnapshot: "Sinaleira" })],
      catalog,
      () => "linha-1",
    );

    expect(rows[0].cargo).toBe("Sinaleira");
  });

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

  /*
   * Nome digitado à mão nunca dependeu de equipe nenhuma, e é a única
   * evidência de que aquela pessoa esteve na frente de serviço. Ele continua
   * atravessando, com a proveniência inteira.
   */
  it("preserva a proveniência do nome digitado à mão", () => {
    const [row] = carryForwardWorkforce(
      [previous(null, { nameSnapshot: "AJUDANTE CONTRATADO NO DIA" })],
      [],
      () => "linha-1",
    );

    expect(row).toMatchObject({
      colaboradorId: "",
      sourceRdoId: source,
      origemItemId: "item-unknown",
      selected: true,
      availability: "AVAILABLE",
      nomeColaborador: "AJUDANTE CONTRATADO NO DIA",
    });
  });

  it("herda a mão de obra manual pelo item de origem sem exigir identidade de acesso", () => {
    const [row] = carryForwardWorkforce(
      [
        previous(null, {
          sourceItemId: "manual-worker-source",
          nameSnapshot: "Maria Servente",
          availability: "AVAILABLE",
        }),
      ],
      catalog,
      () => "manual-worker-next-rdo",
    );

    expect(row).toMatchObject({
      localId: "manual-worker-next-rdo",
      origemItemId: "manual-worker-source",
      sourceRdoId: source,
      colaboradorId: "",
      nomeColaborador: "Maria Servente",
      selected: true,
      availability: "AVAILABLE",
    });
  });

  it("preserva manual legado com disponibilidade desconhecida no fallback", () => {
    const [row] = carryForwardWorkforce(
      [
        previous(null, {
          sourceItemId: "legacy-manual-source",
          nameSnapshot: "José Ajudante",
          availability: "UNKNOWN",
        }),
      ],
      [],
      () => "legacy-manual-next-rdo",
    );

    expect(row).toMatchObject({
      localId: "legacy-manual-next-rdo",
      origemItemId: "legacy-manual-source",
      colaboradorId: "",
      nomeColaborador: "José Ajudante",
      selected: true,
      availability: "AVAILABLE",
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

/*
 * O ofício do Academy é cargo; o perfil de acesso é permissão. Quando o Paulo
 * preenche a função na origem, é ela que o RDO sugere — "Apontadora" era o
 * perfil de sistema vazando para a frente de serviço.
 */
describe("a função do Academy como cargo sugerido", () => {
  it("prefere a função ao perfil de acesso ao adicionar alguém", () => {
    const rows = addAuthorizedWorkforceMember([], "worker-a", catalog);

    expect(rows[0].cargo).toBe("TOPÓGRAFA");
  });

  it("sem função no cadastro, o perfil continua sendo o fallback", () => {
    const rows = addAuthorizedWorkforceMember([], "worker-b", catalog);

    expect(rows[0].cargo).toBe("Operador");
  });
});
