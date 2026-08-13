import { describe, expect, it } from "vitest";

import {
  calibrarPelosMarcos,
  consultaOverpass,
  costurarTrechos,
  referenciaDaRodovia,
  rodoviaDaResposta,
} from "./tracarEixoAutomatico";

/*
 * O gesto que este módulo substitui: dois cliques certeiros sobre a rodovia e
 * os dois quilômetros digitados. A rodovia do cadastro tem geometria no mapa
 * público e os marcos quilométricos têm o km gravado — quatro informações que
 * ninguém deveria precisar digitar de novo.
 */
describe("a referência da rodovia", () => {
  it("normaliza as grafias que o cadastro recebe", () => {
    expect(referenciaDaRodovia("RR-101")).toBe("RR-101");
    expect(referenciaDaRodovia("rr 101")).toBe("RR-101");
    expect(referenciaDaRodovia("RR101")).toBe("RR-101");
    expect(referenciaDaRodovia("RR-0101")).toBe("RR-101");
  });

  it("recusa o que não é referência", () => {
    expect(referenciaDaRodovia("Rodovia do Contorno")).toBeNull();
    expect(referenciaDaRodovia("")).toBeNull();
  });
});

describe("a leitura da resposta do mapa público", () => {
  it("separa trechos da rodovia e marcos com quilômetro", () => {
    const rodovia = rodoviaDaResposta({
      elements: [
        {
          type: "way",
          geometry: [
            { lat: 0, lon: 0 },
            { lat: 0, lon: 0.01 },
          ],
        },
        {
          type: "node",
          lat: 0,
          lon: 0.005,
          tags: { highway: "milestone", distance: "102" },
        },
        { type: "node", lat: 0, lon: 0.006, tags: {} },
        "lixo",
      ],
    });

    expect(rodovia.trechos).toHaveLength(1);
    expect(rodovia.marcos).toEqual([{ lat: 0, lng: 0.005, km: 102 }]);
  });

  it("aceita o km do marco com vírgula", () => {
    const rodovia = rodoviaDaResposta({
      elements: [
        {
          type: "node",
          lat: 1,
          lon: 1,
          tags: { distance: "102,5" },
        },
      ],
    });

    expect(rodovia.marcos[0].km).toBeCloseTo(102.5);
  });

  it("lixo total vira ausência, nunca erro", () => {
    expect(rodoviaDaResposta(null).trechos).toHaveLength(0);
    expect(rodoviaDaResposta("x").marcos).toHaveLength(0);
  });
});

describe("a costura dos trechos", () => {
  it("une pedaços fora de ordem, invertendo quando preciso", () => {
    const costura = costurarTrechos([
      [
        { lat: 0, lng: 0.02 },
        { lat: 0, lng: 0.03 },
      ],
      [
        { lat: 0, lng: 0 },
        { lat: 0, lng: 0.01 },
      ],
      // Este chega de trás para frente.
      [
        { lat: 0, lng: 0.02 },
        { lat: 0, lng: 0.01 },
      ],
    ]);

    expect(costura.map((p) => p.lng)).toEqual([0, 0.01, 0.02, 0.03]);
  });

  /* O pedaço de outra pista, longe demais, não entra na régua. */
  it("deixa de fora o trecho que não encosta", () => {
    const costura = costurarTrechos([
      [
        { lat: 0, lng: 0 },
        { lat: 0, lng: 0.01 },
      ],
      [
        { lat: 1, lng: 1 },
        { lat: 1, lng: 1.01 },
      ],
    ]);

    expect(costura).toHaveLength(2);
  });
});

describe("a calibração pelos marcos", () => {
  // Linha reta no equador: 0,01 grau ≈ 1,113 km.
  const linha = [
    { lat: 0, lng: 0 },
    { lat: 0, lng: 0.01 },
    { lat: 0, lng: 0.02 },
    { lat: 0, lng: 0.03 },
  ];

  it("dá quilômetro às pontas a partir de dois marcos", () => {
    const eixo = calibrarPelosMarcos(linha, [
      { lat: 0, lng: 0.005, km: 100.55 },
      { lat: 0, lng: 0.025, km: 102.78 },
    ]);

    expect(eixo).not.toBeNull();
    // O marco de 100,55 está a ~0,556 km do início: a ponta sai perto de 100.
    expect(eixo?.kmInicial).toBeCloseTo(100, 1);
    expect(eixo?.kmFinal).toBeCloseTo(103.3, 1);
    expect(eixo?.marcosUsados).toBe(2);
  });

  it("recusa marcos colados, que não seguram régua", () => {
    expect(
      calibrarPelosMarcos(linha, [
        { lat: 0, lng: 0.005, km: 100 },
        { lat: 0, lng: 0.006, km: 100.1 },
      ]),
    ).toBeNull();
  });

  it("descarta o marco longe da linha — é de outra pista", () => {
    expect(
      calibrarPelosMarcos(linha, [
        { lat: 0, lng: 0.005, km: 100 },
        { lat: 0.5, lng: 0.02, km: 200 },
      ]),
    ).toBeNull();
  });

  /* Marcos que dizem 10 km onde a linha tem 1 não descrevem esta rodovia. */
  it("recusa a régua cuja escala não fecha com a geometria", () => {
    expect(
      calibrarPelosMarcos(linha, [
        { lat: 0, lng: 0.005, km: 100 },
        { lat: 0, lng: 0.025, km: 130 },
      ]),
    ).toBeNull();
  });
});

describe("a consulta ao mapa público", () => {
  it("pede a rodovia pela referência e os marcos na caixa da cidade", () => {
    const consulta = consultaOverpass("RR-101", [
      [-47.7, -22.5],
      [-47.3, -22.2],
    ]);

    expect(consulta).toContain('ref"~"^RR[- ]?101($|;)"');
    expect(consulta).toContain("-22.5,-47.7,-22.2,-47.3");
    expect(consulta).toContain('"highway"="milestone"');
  });
});
