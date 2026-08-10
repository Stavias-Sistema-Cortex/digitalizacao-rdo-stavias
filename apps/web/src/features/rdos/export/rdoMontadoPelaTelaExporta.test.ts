import { describe, expect, it } from "vitest";

import { createEmptyRdo } from "../createEmptyRdo";
import { adicionarEquipamentosDoCatalogo } from "../equipamentosDoRdo";
import { addAuthorizedWorkforceMember } from "../rdoWorkforceCarryForward";
import type { RdoContextCollaborator, RdoContextEquipment } from "../rdoLookupApi";
import type { RdoDraft } from "../rdo.types";
import { buildRdoExportProjection, type RdoWorkbookSnapshot } from "./rdoExportProjection";

/**
 * A exportação de um RDO montado do jeito que a tela monta.
 *
 * <p>Os outros testes de exportação partem de um rascunho escrito à mão no
 * próprio teste, com quantidade e vínculo preenchidos em toda linha. Nenhum
 * deles passa pelas funções que a tela realmente usa para pôr gente e máquina
 * no RDO — e era exatamente ali que a exportação parava: a escolha em lista
 * dupla põe a pessoa e a máquina no rascunho sem quantidade nenhuma, porque
 * nesse modelo a linha é uma pessoa e uma máquina, não um total digitado.
 */

const CATALOGO_PESSOAS: RdoContextCollaborator[] = [
  {
    id: "col-1",
    codigoColaborador: "MAT-1",
    nome: "Ana Apontadora",
    papelNaObra: "APONTADOR",
    nomePerfil: "Apontador",
  },
  {
    id: "col-2",
    codigoColaborador: "MAT-2",
    nome: "Bruno Motorista",
    papelNaObra: "MOTORISTA",
    nomePerfil: "Motorista",
  },
];

const CATALOGO_MAQUINAS: RdoContextEquipment[] = [
  {
    id: "asset-1",
    codigoExterno: "FRE004",
    nome: "Fresadora",
    categoria: "FRESADORA",
  },
];

function rdoMontadoPelaTela(): RdoDraft {
  const base: RdoDraft = {
    ...createEmptyRdo(),
    id: "rdo-77",
    obraId: "obra-77",
    numeroRdo: "RDO-0077",
    dataRdo: "2026-07-20",
    condicaoNoite: "BOM",
    apontadorRdo: "Ana Apontadora",
  };
  const comPessoas = CATALOGO_PESSOAS.reduce(
    (rascunho, pessoa) => ({
      ...rascunho,
      maoObra: addAuthorizedWorkforceMember(
        rascunho.maoObra,
        pessoa.id,
        CATALOGO_PESSOAS,
      ),
    }),
    base,
  );
  return {
    ...comPessoas,
    equipamentos: adicionarEquipamentosDoCatalogo(
      comPessoas.equipamentos,
      ["asset-1"],
      CATALOGO_MAQUINAS,
    ),
  };
}

function snapshot(): RdoWorkbookSnapshot {
  return {
    obra: { id: "obra-77", nome: "Obra Sul", codigoContrato: "CW-077" },
    rdo: rdoMontadoPelaTela(),
  };
}

describe("exportação de um RDO montado pela tela", () => {
  it("projeta sem exigir a quantidade que a lista dupla não pede", () => {
    expect(() => buildRdoExportProjection(snapshot())).not.toThrow();
  });

  it("conta uma pessoa por linha escolhida, agrupada por cargo", () => {
    const { workforce } = buildRdoExportProjection(snapshot());

    expect(workforce).toEqual([
      { role: "Apontador", subcontracted: false, quantity: 1 },
      { role: "Motorista", subcontracted: false, quantity: 1 },
    ]);
  });

  it("soma as pessoas do mesmo cargo em uma linha só", () => {
    const base = snapshot();
    const rdo = {
      ...base.rdo,
      maoObra: addAuthorizedWorkforceMember(
        base.rdo.maoObra,
        "col-3",
        [
          ...CATALOGO_PESSOAS,
          {
            id: "col-3",
            codigoColaborador: "MAT-3",
            nome: "Carla Motorista",
            papelNaObra: "MOTORISTA",
            nomePerfil: "Motorista",
          },
        ],
      ),
    };

    const { workforce } = buildRdoExportProjection({ ...base, rdo });

    expect(
      workforce.find((grupo) => grupo.role === "Motorista")?.quantity,
    ).toBe(2);
  });

  it("conta uma máquina por linha escolhida do parque da obra", () => {
    const { equipment } = buildRdoExportProjection(snapshot());

    expect(equipment).toHaveLength(1);
    expect(equipment[0]).toMatchObject({
      assetId: "asset-1",
      prefixo: "FRE004",
      descricao: "Fresadora",
    });
  });

  /*
   * O parque é o que a obra tem; máquina de lá sai como própria, e o campo
   * Vínculo segue aberto para quem precisar corrigir. Inventar seria dizer
   * "locado" sem base — próprio é o que o cadastro já afirma ao listá-la.
   */
  it("trata a máquina vinda do parque da obra como própria", () => {
    const projecao = buildRdoExportProjection(snapshot());

    expect(projecao.equipment[0].tipoVinculo).toBe("PROPRIO");
  });

  /*
   * A tolerância é para a linha que se identifica sozinha. Uma linha digitada
   * à mão, sem pessoa e sem máquina atrás dela, continua precisando do número:
   * "Motorista" sem nome e sem quantidade não diz quantos motoristas foram.
   */
  it("ainda exige quantidade na linha de cargo sem pessoa", () => {
    const base = snapshot();
    const rdo = {
      ...base.rdo,
      maoObra: [
        ...base.rdo.maoObra,
        {
          ...base.rdo.maoObra[0],
          localId: "linha-avulsa",
          colaboradorId: "",
          nomeColaborador: "",
          cargo: "Rasteleiro",
          quantidade: "",
        },
      ],
    };

    expect(() => buildRdoExportProjection({ ...base, rdo })).toThrow(
      /quantidade/i,
    );
  });

  it("ainda exige vínculo na máquina digitada fora do parque", () => {
    const base = snapshot();
    const rdo = {
      ...base.rdo,
      equipamentos: [
        ...base.rdo.equipamentos,
        {
          ...base.rdo.equipamentos[0],
          localId: "maquina-avulsa",
          assetId: "",
          prefixo: "",
          descricao: "Rompedor alugado na cidade",
          tipoVinculo: "",
          quantidade: "",
        },
      ],
    };

    expect(() => buildRdoExportProjection({ ...base, rdo })).toThrow(
      /vínculo|quantidade/i,
    );
  });
});
