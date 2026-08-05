import { describe, expect, it } from "vitest";

import { createEmptyEquipamento } from "./createEmptyRdo";
import {
  adicionarEquipamentosDoCatalogo,
  equipamentosDisponiveis,
  equipamentosEscolhidos,
  removerEquipamentos,
} from "./equipamentosDoRdo";
import type { EquipamentoDraft } from "./rdo.types";
import type { RdoContextEquipment } from "./rdoLookupApi";

const PARQUE: RdoContextEquipment[] = [
  {
    id: "asset-esc",
    codigoExterno: "ESC-07",
    nome: "Escavadeira Hidráulica",
    categoria: "Terraplenagem",
  },
  {
    id: "asset-cam",
    codigoExterno: "CAM-12",
    nome: "Caminhão Basculante",
    categoria: "Transporte",
  },
  { id: "asset-sem-nome", codigoExterno: "ROL-03", nome: null, categoria: null },
];

function noRdo(...itens: Partial<EquipamentoDraft>[]): EquipamentoDraft[] {
  return itens.map((item, indice) => ({
    ...createEmptyEquipamento(),
    localId: `linha-${indice}`,
    ...item,
  }));
}

let contador = 0;
const proximoId = () => `novo-${(contador += 1)}`;

describe("o parque da obra visto pelos dois lados", () => {
  it("oferece só o que ainda não está no RDO", () => {
    const disponiveis = equipamentosDisponiveis(
      PARQUE,
      noRdo({ assetId: "asset-cam" }),
    );

    expect(disponiveis.map((item) => item.id))
      .toEqual(["asset-esc", "asset-sem-nome"]);
  });

  /*
   * A máquina somada à mão não tem cadastro. Se o assetId vazio contasse como
   * chave, a primeira linha manual esconderia um item do parque.
   */
  it("linha manual sem cadastro não esconde nada do parque", () => {
    const disponiveis = equipamentosDisponiveis(
      PARQUE,
      noRdo({ assetId: "", descricao: "Betoneira do empreiteiro" }),
    );

    expect(disponiveis).toHaveLength(3);
  });

  it("identifica pelo código quando não há nome", () => {
    const [, , semNome] = equipamentosDisponiveis(PARQUE, []);

    expect(semNome.titulo).toBe("ROL-03");
    // O código já é o título; repeti-lo no detalhe seria ruído.
    expect(semNome.detalhe).toBeNull();
  });

  it("descreve com código e categoria", () => {
    const [escavadeira] = equipamentosDisponiveis(PARQUE, []);

    expect(escavadeira.titulo).toBe("Escavadeira Hidráulica");
    expect(escavadeira.detalhe).toBe("ESC-07 · Terraplenagem");
  });

  /*
   * O lado escolhido anda pelo localId: a linha manual precisa aparecer e
   * poder sair, e ela não tem assetId para servir de chave.
   */
  it("mostra a linha manual entre as escolhidas", () => {
    const escolhidos = equipamentosEscolhidos(
      noRdo(
        { assetId: "asset-esc", descricao: "Escavadeira Hidráulica", prefixo: "ESC-07" },
        { descricao: "Betoneira do empreiteiro" },
      ),
    );

    expect(escolhidos).toEqual([
      {
        id: "linha-0",
        titulo: "Escavadeira Hidráulica",
        detalhe: "ESC-07",
      },
      { id: "linha-1", titulo: "Betoneira do empreiteiro", detalhe: null },
    ]);
  });

  it("leva várias máquinas de uma vez, já preenchidas pelo cadastro", () => {
    const resultado = adicionarEquipamentosDoCatalogo(
      [],
      ["asset-esc", "asset-cam"],
      PARQUE,
      proximoId,
    );

    expect(resultado).toHaveLength(2);
    expect(resultado[0]).toMatchObject({
      assetId: "asset-esc",
      prefixo: "ESC-07",
      descricao: "Escavadeira Hidráulica",
      tipoEquipamento: "Terraplenagem",
      quantidade: "",
      horaInicio: "",
    });
    expect(resultado[1]).toMatchObject({ assetId: "asset-cam", prefixo: "CAM-12" });
  });

  /*
   * Duas linhas do mesmo asset viram duas horas de máquina para o mesmo
   * equipamento no mesmo dia — dobra a medição sem que ninguém veja.
   */
  it("não lança a mesma máquina duas vezes", () => {
    const jaTem = noRdo({ assetId: "asset-esc" });

    const resultado = adicionarEquipamentosDoCatalogo(
      jaTem,
      ["asset-esc"],
      PARQUE,
      proximoId,
    );

    expect(resultado).toHaveLength(1);
    expect(resultado[0]).toBe(jaTem[0]);
  });

  it("recusa máquina que não é do parque da obra", () => {
    expect(() =>
      adicionarEquipamentosDoCatalogo([], ["asset-de-outra-obra"], PARQUE)
    ).toThrow("Equipamento não pertence ao parque desta obra.");
  });

  it("tira várias de uma vez e preserva as demais", () => {
    const linhas = noRdo(
      { assetId: "asset-esc" },
      { assetId: "asset-cam" },
      { descricao: "Betoneira" },
    );

    expect(removerEquipamentos(linhas, ["linha-0", "linha-2"]))
      .toEqual([linhas[1]]);
  });
});
