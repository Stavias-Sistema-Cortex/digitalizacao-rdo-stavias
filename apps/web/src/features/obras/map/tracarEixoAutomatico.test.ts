import { describe, expect, it } from "vitest";

import {
  TracadoRecusado,
  calibrarPelosMarcos,
  consultaOverpass,
  costurarTrechos,
  referenciaDaRodovia,
  rodoviaDaResposta,
  tracarEixoPelaRodovia,
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

  /*
   * A pista dupla. Cada sentido é uma linha própria a poucas dezenas de metros
   * da outra, e nas bordas do recorte as pontas das duas ficam dentro da
   * tolerância de emenda. A costura descia uma pista e voltava pela outra —
   * em Pirassununga, 64,7 km de linha para um vão real de 32,3 — e uma régua
   * dobrada não calibra com marco nenhum: o quilômetro cresce na ida e
   * "volta" na vinda.
   */
  it("não volta pela pista oposta da dupla", () => {
    // Pista sul, sentido leste, em três pedaços; 0,0004° ≈ 44 m ao lado, a
    // pista norte percorre o mesmo corredor.
    const pistaSul: [number, number][][] = [
      [[0, 0], [0, 0.004], [0, 0.008]],
      [[0, 0.008], [0, 0.012], [0, 0.016]],
      [[0, 0.016], [0, 0.02], [0, 0.024]],
    ];
    const pistaNorte: [number, number][][] = [
      [[0.0004, 0.024], [0.0004, 0.016]],
      [[0.0004, 0.016], [0.0004, 0.008]],
      [[0.0004, 0.008], [0.0004, 0]],
    ];
    const costura = costurarTrechos(
      [...pistaSul, ...pistaNorte].map((pedaco) =>
        pedaco.map(([lat, lng]) => ({ lat, lng })),
      ),
    );

    // Só a pista sul: nenhum ponto da volta, e o leste sempre crescendo.
    expect(costura.every((ponto) => ponto.lat === 0)).toBe(true);
    for (let i = 1; i < costura.length; i += 1) {
      expect(costura[i].lng).toBeGreaterThan(costura[i - 1].lng);
    }
  });

  /*
   * O que a regra da volta NÃO pode comer: a serra. Depois do grampo, a
   * continuação corre perto da costura por um instante e diverge no resto do
   * corpo — só a pista oposta corre colada do começo ao fim.
   */
  it("aceita o zigue-zague da serra, que diverge depois do grampo", () => {
    const subida = [
      { lat: 0, lng: 0 },
      { lat: 0, lng: 0.01 },
      { lat: 0, lng: 0.02 },
    ];
    // Volta do grampo: nasce a 44 m da costura e abre até ~2 km.
    const voltaDoGrampo = [
      { lat: 0.0004, lng: 0.02 },
      { lat: 0.005, lng: 0.012 },
      { lat: 0.012, lng: 0.004 },
      { lat: 0.02, lng: 0 },
    ];
    const costura = costurarTrechos([subida, voltaDoGrampo]);

    expect(costura.some((ponto) => ponto.lat === 0.02)).toBe(true);
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

/*
 * O Overpass é infraestrutura voluntária e compartilhada: o espelho principal
 * vive ocupado em horário comercial. Com um endereço só, "ocupado agora" virava
 * "não deu para traçar" — e a rodovia estava lá o tempo todo, no espelho
 * seguinte, servindo a mesma base.
 */
describe("a insistência entre os espelhos do mapa público", () => {
  const ENDERECO = { rodovia: "RR-101", cidade: "Cidade" };
  const CIDADE = [
    {
      lat: "0",
      lon: "0.015",
      boundingbox: ["-0.1", "0.1", "-0.1", "0.1"],
    },
  ];
  /* Uma reta com dois marcos que sustentam a régua. */
  const MAPA = {
    elements: [
      {
        type: "way",
        geometry: [
          { lat: 0, lon: 0 },
          { lat: 0, lon: 0.03 },
        ],
      },
      {
        type: "node",
        lat: 0,
        lon: 0.005,
        tags: { highway: "milestone", distance: "100" },
      },
      {
        type: "node",
        lat: 0,
        lon: 0.025,
        tags: { highway: "milestone", distance: "102.2" },
      },
    ],
  };

  function respostaJson(corpo: unknown): Response {
    return {
      ok: true,
      status: 200,
      json: async () => corpo,
    } as unknown as Response;
  }

  it("segue para o próximo espelho quando o primeiro está ocupado", async () => {
    const pedidos: string[] = [];
    const eixo = await tracarEixoPelaRodovia(
      ENDERECO,
      (async (entrada: string) => {
        pedidos.push(String(entrada));
        if (String(entrada).includes("nominatim")) return respostaJson(CIDADE);
        if (pedidos.filter((p) => p.includes("interpreter")).length === 1) {
          return { ok: false, status: 429 } as unknown as Response;
        }
        return respostaJson(MAPA);
      }) as unknown as typeof fetch,
    );

    expect(eixo.marcosUsados).toBe(2);
    const espelhos = pedidos.filter((p) => p.includes("interpreter"));
    expect(espelhos).toHaveLength(2);
    expect(espelhos[0]).not.toBe(espelhos[1]);
  });

  /* Consulta malformada é igual em todo espelho: insistir só faz esperar. */
  it("não repete a pergunta que o espelho recusou por conteúdo", async () => {
    const espelhos: string[] = [];
    await expect(
      tracarEixoPelaRodovia(
        ENDERECO,
        (async (entrada: string) => {
          if (String(entrada).includes("nominatim")) return respostaJson(CIDADE);
          espelhos.push(String(entrada));
          return { ok: false, status: 400 } as unknown as Response;
        }) as unknown as typeof fetch,
      ),
    ).rejects.toThrow(TracadoRecusado);

    expect(espelhos).toHaveLength(1);
  });

  it("com todos calados, a frase diz o que houve no último", async () => {
    await expect(
      tracarEixoPelaRodovia(
        ENDERECO,
        (async (entrada: string) => {
          if (String(entrada).includes("nominatim")) return respostaJson(CIDADE);
          throw new Error("rede caiu");
        }) as unknown as typeof fetch,
      ),
    ).rejects.toThrow(/não respondeu/);
  });

  /*
   * O 200 vazio de um espelho não é o mapa dizendo que a rodovia não existe —
   * pode ser um espelho regional servindo outra parte do mundo. Foi um deles,
   * respondendo vazio para qualquer caixa brasileira, que transformava
   * "espelho ocupado" em "a rodovia não aparece mapeada" sobre uma rodovia
   * inteiramente mapeada. O vazio só vale quando outro espelho o confirma.
   */
  it("pede segunda opinião quando o espelho responde vazio", async () => {
    const espelhos: string[] = [];
    const eixo = await tracarEixoPelaRodovia(
      ENDERECO,
      (async (entrada: string) => {
        if (String(entrada).includes("nominatim")) return respostaJson(CIDADE);
        espelhos.push(String(entrada));
        if (espelhos.length === 1) return respostaJson({ elements: [] });
        return respostaJson(MAPA);
      }) as unknown as typeof fetch,
    );

    expect(eixo.marcosUsados).toBe(2);
    expect(espelhos).toHaveLength(2);
  });

  /* Vazio confirmado por toda a lista é resposta: a rodovia não está lá. */
  it("aceita o vazio quando todos os espelhos o confirmam", async () => {
    const espelhos: string[] = [];
    await expect(
      tracarEixoPelaRodovia(
        ENDERECO,
        (async (entrada: string) => {
          if (String(entrada).includes("nominatim")) return respostaJson(CIDADE);
          espelhos.push(String(entrada));
          return respostaJson({ elements: [] });
        }) as unknown as typeof fetch,
      ),
    ).rejects.toThrow(/não aparece mapeada/);

    expect(espelhos.length).toBeGreaterThan(1);
  });
});

describe("a consulta ao mapa público", () => {
  it("pede a rodovia pela referência, em qualquer posição da lista", () => {
    const consulta = consultaOverpass("RR-101", [
      [-47.7, -22.5],
      [-47.3, -22.2],
    ]);

    /*
     * O (^|;) não é enfeite: a rodovia concorrida pode listar a outra
     * primeiro — "BR-050;SP-330" —, e o regex ancorado no começo deixava a
     * rodovia inteira fora da resposta para quem cadastrou o outro nome.
     */
    expect(consulta).toContain('ref"~"(^|;)RR[- ]?101($|;)"');
  });

  it("cresce a caixa da cidade para alcançar o marco vizinho", () => {
    const consulta = consultaOverpass("RR-101", [
      [-47.7, -22.5],
      [-47.3, -22.2],
    ]);

    /*
     * A caixa municipal raramente contém dois marcos com quilômetros
     * distintos — na malha paulista eles vêm a cada 30–40 km. Sem a margem, a
     * régua ficava com um km só e a calibração recusava uma rodovia
     * perfeitamente mapeada.
     */
    expect(consulta).toContain("-22.8,-48,-21.9,-47");
  });

  it("busca os marcos colados na rodovia, não soltos na caixa", () => {
    const consulta = consultaOverpass("RR-101", [
      [-47.7, -22.5],
      [-47.3, -22.2],
    ]);

    /*
     * O marco solto na caixa podia ser de outra estrada cruzando a cidade —
     * em Pirassununga, os km 104 da SP-201 entravam na resposta e os km 182 e
     * 253 da própria rodovia, logo além da divisa, ficavam de fora.
     */
    expect(consulta).toContain("around.w.rodovia:250");
    expect(consulta).toContain('"highway"="milestone"');
    expect(consulta).toContain('["distance"]');
  });
});
