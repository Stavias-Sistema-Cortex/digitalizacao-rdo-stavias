import { describe, expect, it } from "vitest";

import {
  servicosDistintos,
  timbreDoSegmento,
  timbresDeServico,
} from "./servicosDoTrecho";
import type { SegmentoTrecho } from "./trechoGeometry";

function segmento(
  servicoNome: string | null,
  overrides: Partial<SegmentoTrecho> = {},
): SegmentoTrecho {
  return {
    id: `seg-${servicoNome ?? "sem"}-${Math.random()}`,
    origem: "EXECUCAO_SERVICO",
    rdoId: "rdo-1",
    numeroRdo: "RDO-0001",
    data: "2026-08-03",
    rdoStatus: "ENVIADO",
    procedencia: "SERVIDOR",
    servicoNome,
    subtrecho: null,
    sentido: null,
    pista: "SIMPLES",
    faixa: "1",
    kmInicial: 12,
    kmFinal: 13,
    estacaInicial: null,
    estacaFinal: null,
    extensaoM: 1000,
    larguraM: null,
    areaM2: null,
    massaTonelada: null,
    status: "VALIDADA",
    pistaInferida: false,
    ...overrides,
  } as SegmentoTrecho;
}

/**
 * O estado já é dono do preenchimento e da textura do bloco. O serviço ganhou
 * um canal próprio para não disputar com ele — e um canal que só aparece
 * quando há o que distinguir.
 */
describe("timbre do serviço no trecho", () => {
  it("não gasta canal nenhum quando há um serviço só", () => {
    const timbres = timbresDeServico([
      segmento("Fresagem"),
      segmento("Fresagem"),
    ]);

    expect(timbres.size).toBe(0);
    expect(timbreDoSegmento(segmento("Fresagem"), timbres)).toBeNull();
  });

  it("separa os serviços na ordem em que aparecem no trecho", () => {
    const segmentos = [
      segmento("Fresagem"),
      segmento("Imprimação"),
      segmento("Fresagem"),
      segmento("Capa"),
    ];

    expect(servicosDistintos(segmentos)).toEqual([
      "Fresagem",
      "Imprimação",
      "Capa",
    ]);

    const timbres = timbresDeServico(segmentos);
    expect(timbres.get("Fresagem")).toBe(1);
    expect(timbres.get("Imprimação")).toBe(2);
    expect(timbres.get("Capa")).toBe(3);
    // O mesmo serviço mantém o mesmo timbre onde quer que reapareça.
    expect(timbreDoSegmento(segmentos[2], timbres)).toBe(1);
  });

  /** Sem nome de serviço não há o que separar; o bloco fica sem timbre. */
  it("deixa sem timbre o bloco que não nomeia serviço", () => {
    const segmentos = [
      segmento("Fresagem"),
      segmento(null, { subtrecho: "Acesso norte" }),
    ];
    const timbres = timbresDeServico(segmentos);

    expect(timbres.size).toBe(0);
    expect(timbreDoSegmento(segmentos[1], timbres)).toBeNull();
  });

  /** Passando de seis serviços a série recomeça; a legenda desfaz o empate. */
  it("recomeça a série depois do sexto serviço", () => {
    const nomes = ["A", "B", "C", "D", "E", "F", "G"];
    const timbres = timbresDeServico(nomes.map((nome) => segmento(nome)));

    expect(timbres.get("F")).toBe(6);
    expect(timbres.get("G")).toBe(1);
  });
});
