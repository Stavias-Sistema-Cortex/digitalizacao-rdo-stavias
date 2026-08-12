import { describe, expect, it } from "vitest";

import { encarregadosDoRateio, filtrarRateio } from "./filtrosDoRateio";
import {
  apurarRateio,
  type ApontamentoDeMaoDeObra,
} from "./rateioDeMaoDeObra";

function apontamento(
  parcial: Partial<ApontamentoDeMaoDeObra> & { data: string; obraId: string },
): ApontamentoDeMaoDeObra {
  return {
    colaboradorId: "col-1",
    nome: "PESSOA UM",
    funcao: "AJUDANTE DE OBRA",
    encarregado: "FRENTE A",
    rdoId: `rdo-${parcial.obraId}-${parcial.data}`,
    ...parcial,
  };
}

/*
 * Quinze dias numa obra e quinze noutra. É o caso que revela se o filtro
 * recalcula a conta: se recalcular, filtrando uma obra a pessoa vira 100% dela.
 */
const MEIO_A_MEIO = apurarRateio([
  ...Array.from({ length: 15 }, (_, indice) =>
    apontamento({
      data: `2026-07-${String(indice + 1).padStart(2, "0")}`,
      obraId: "obra-a",
    }),
  ),
  ...Array.from({ length: 15 }, (_, indice) =>
    apontamento({
      data: `2026-07-${String(indice + 16).padStart(2, "0")}`,
      obraId: "obra-b",
    }),
  ),
]);

describe("o filtro da tela", () => {
  it("não recalcula a fatia da pessoa ao esconder uma obra", () => {
    const filtrado = filtrarRateio(MEIO_A_MEIO, { obraIds: ["obra-a"] });

    const [pessoa] = filtrado.colaboradores;
    expect(pessoa.fracaoPorObra.get("obra-a")).toBe(0.5);
    // A fatia da obra escondida continua existindo no dado; a tela é que não a
    // mostra. É isso que impede o número visível de virar 100%.
    expect(pessoa.fracaoPorObra.get("obra-b")).toBe(0.5);
    expect(pessoa.diasApontados).toBe(30);
  });

  it("mostra só as colunas de obra escolhidas", () => {
    const filtrado = filtrarRateio(MEIO_A_MEIO, { obraIds: ["obra-a"] });
    expect(filtrado.obraIds).toEqual(["obra-a"]);
  });

  it("sem escolha de obra, mostra todas", () => {
    const filtrado = filtrarRateio(MEIO_A_MEIO, {});
    expect([...filtrado.obraIds].sort()).toEqual(["obra-a", "obra-b"]);
  });

  it("tira da lista quem não passou por nenhuma obra escolhida", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "obra-a" }),
      apontamento({
        data: "2026-07-01",
        obraId: "obra-b",
        colaboradorId: "col-2",
        nome: "PESSOA DOIS",
      }),
    ]);

    const filtrado = filtrarRateio(rateio, { obraIds: ["obra-a"] });

    expect(filtrado.colaboradores.map((pessoa) => pessoa.nome)).toEqual([
      "PESSOA UM",
    ]);
  });

  it("conta o total da obra apenas com quem está visível", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "obra-a" }),
      apontamento({
        data: "2026-07-01",
        obraId: "obra-a",
        colaboradorId: "col-2",
        nome: "PESSOA DOIS",
        encarregado: "FRENTE B",
      }),
    ]);

    const filtrado = filtrarRateio(rateio, { encarregado: "FRENTE B" });

    expect(filtrado.colaboradores).toHaveLength(1);
    expect(filtrado.totaisPorObra.get("obra-a")?.pessoas).toBe(1);
    expect(filtrado.totaisPorObra.get("obra-a")?.diasApontados).toBe(1);
    expect(filtrado.diasApontadosNoTotal).toBe(1);
  });

  it("procura por nome e por função, sem acento e sem caixa", () => {
    const rateio = apurarRateio([
      apontamento({
        data: "2026-07-01",
        obraId: "obra-a",
        nome: "JOSÉ CÍCERO",
        funcao: "OPERADOR",
      }),
      apontamento({
        data: "2026-07-01",
        obraId: "obra-a",
        colaboradorId: "col-2",
        nome: "ANA",
        funcao: "MOTORISTA",
      }),
    ]);

    expect(
      filtrarRateio(rateio, { busca: "jose cicero" }).colaboradores,
    ).toHaveLength(1);
    expect(
      filtrarRateio(rateio, { busca: "motorista" }).colaboradores[0].nome,
    ).toBe("ANA");
    expect(filtrarRateio(rateio, { busca: "  " }).colaboradores).toHaveLength(
      2,
    );
  });

  it("não deixa a obra escolhida sem ninguém virar coluna de zeros", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "obra-a" }),
    ]);

    const filtrado = filtrarRateio(rateio, {
      obraIds: ["obra-a", "obra-sem-ninguem"],
    });

    expect(filtrado.obraIds).toEqual(["obra-a"]);
  });

  it("no dia dividido, só a metade visível entra no total da obra", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "obra-a" }),
      apontamento({ data: "2026-07-01", obraId: "obra-b" }),
    ]);

    const filtrado = filtrarRateio(rateio, { obraIds: ["obra-a"] });

    expect(filtrado.totaisPorObra.get("obra-a")?.diasApontados).toBe(0.5);
    expect(filtrado.totaisPorObra.has("obra-b")).toBe(false);
  });

  it("lista as frentes do período para o seletor", () => {
    const rateio = apurarRateio([
      apontamento({ data: "2026-07-01", obraId: "obra-a", encarregado: "ZE" }),
      apontamento({
        data: "2026-07-01",
        obraId: "obra-a",
        colaboradorId: "col-2",
        nome: "DOIS",
        encarregado: "ANA",
      }),
      apontamento({
        data: "2026-07-01",
        obraId: "obra-a",
        colaboradorId: "col-3",
        nome: "TRES",
        encarregado: "",
      }),
    ]);

    expect(encarregadosDoRateio(rateio)).toEqual(["ANA", "ZE"]);
  });
});
