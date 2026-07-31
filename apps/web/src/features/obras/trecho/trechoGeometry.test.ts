import { describe, expect, it } from "vitest";

import {
  blocoDoSegmento,
  calcularEscalaKm,
  estadoDoSegmento,
  formatarKm,
  ladosDoCanteiro,
  marcosDeLimite,
  marcosKm,
  pistasDoTrecho,
  posicaoPercentual,
  rotuloDoSegmento,
  segmentosPosicionaveis,
  type SegmentoTrecho,
} from "./trechoGeometry";

function segmento(overrides: Partial<SegmentoTrecho> = {}): SegmentoTrecho {
  return {
    id: "seg-1",
    origem: "RDO_CONTROLE",
    rdoId: "rdo-1",
    numeroRdo: "RDO-1",
    data: "2026-03-02",
    servicoNome: "Recapeamento",
    subtrecho: null,
    sentido: "SUL",
    pista: "SUL",
    faixa: "1",
    kmInicial: 172,
    kmFinal: 171,
    estacaInicial: null,
    estacaFinal: null,
    extensaoM: 1000,
    larguraM: null,
    areaM2: null,
    massaTonelada: null,
    status: "VALIDADA",
    ...overrides,
  };
}

describe("segmentosPosicionaveis", () => {
  it("descarta segmentos sem os dois extremos quilométricos", () => {
    const posicionaveis = segmentosPosicionaveis([
      segmento({ id: "a" }),
      segmento({ id: "b", kmInicial: null }),
      segmento({ id: "c", kmFinal: null }),
      segmento({ id: "d", kmInicial: null, kmFinal: null }),
    ]);

    expect(posicionaveis.map((item) => item.id)).toEqual(["a"]);
  });

  it("mantém o quilômetro zero, que é uma marcação legítima", () => {
    const posicionaveis = segmentosPosicionaveis([
      segmento({ id: "a", kmInicial: 0, kmFinal: 0.5 }),
    ]);

    expect(posicionaveis).toHaveLength(1);
  });
});

describe("calcularEscalaKm", () => {
  it("envolve o trecho com folga de contexto de um quilômetro", () => {
    const escala = calcularEscalaKm([segmento({ kmInicial: 172, kmFinal: 171 })]);

    expect(escala).toEqual({ inicio: 170, fim: 173, decrescente: true });
  });

  it("reconhece o sentido crescente quando o km final é maior", () => {
    const escala = calcularEscalaKm([
      segmento({ kmInicial: 309.04, kmFinal: 309.2 }),
    ]);

    expect(escala?.decrescente).toBe(false);
  });

  it("nunca deixa o início da régua abaixo do km zero", () => {
    const escala = calcularEscalaKm([segmento({ kmInicial: 0.2, kmFinal: 0.4 })]);

    expect(escala?.inicio).toBe(0);
  });

  it("tira o sentido de leitura da produção e não do planejamento", () => {
    const escala = calcularEscalaKm([
      segmento({ id: "plan", origem: "PROGRAMACAO", kmInicial: 171, kmFinal: 174 }),
      segmento({ id: "exec", origem: "RDO_CONTROLE", kmInicial: 174, kmFinal: 171 }),
    ]);

    expect(escala?.decrescente).toBe(true);
  });

  it("devolve nulo quando nenhum segmento é posicionável", () => {
    expect(calcularEscalaKm([])).toBeNull();
    expect(calcularEscalaKm([segmento({ kmInicial: null })])).toBeNull();
  });
});

describe("posicaoPercentual", () => {
  const escala = { inicio: 170, fim: 174, decrescente: false };

  it("mapeia os extremos da escala em 0% e 100%", () => {
    expect(posicaoPercentual(170, escala)).toBe(0);
    expect(posicaoPercentual(174, escala)).toBe(100);
    expect(posicaoPercentual(172, escala)).toBe(50);
  });

  it("inverte a leitura no sentido decrescente", () => {
    const decrescente = { inicio: 170, fim: 174, decrescente: true };

    expect(posicaoPercentual(174, decrescente)).toBe(0);
    expect(posicaoPercentual(170, decrescente)).toBe(100);
  });

  it("mantém o valor dentro da faixa desenhável", () => {
    expect(posicaoPercentual(160, escala)).toBe(0);
    expect(posicaoPercentual(200, escala)).toBe(100);
  });
});

describe("marcosKm", () => {
  it("desenha um marco por quilômetro inteiro da escala", () => {
    const escala = { inicio: 170, fim: 173, decrescente: true };

    expect(marcosKm(escala).map((marco) => marco.km)).toEqual([
      173, 172, 171, 170,
    ]);
  });

  it("ordena da esquerda para a direita no sentido crescente", () => {
    const escala = { inicio: 308, fim: 311, decrescente: false };

    expect(marcosKm(escala).map((marco) => marco.km)).toEqual([
      308, 309, 310, 311,
    ]);
  });
});

describe("marcosDeLimite", () => {
  it("marca somente os extremos da obra, não cada ponta de lançamento", () => {
    const escala = { inicio: 169, fim: 175, decrescente: true };
    const limites = marcosDeLimite(escala, [
      segmento({ id: "a", kmInicial: 174, kmFinal: 171 }),
      segmento({ id: "b", kmInicial: 173, kmFinal: 172 }),
      segmento({ id: "c", kmInicial: 172, kmFinal: 171 }),
    ]);

    expect(limites).toHaveLength(2);
    expect(limites[0]).toMatchObject({ km: 174, limite: "INICIO" });
    expect(limites[1]).toMatchObject({ km: 171, limite: "FIM" });
  });

  it("inverte os extremos no sentido crescente", () => {
    const escala = { inicio: 308, fim: 311, decrescente: false };
    const limites = marcosDeLimite(escala, [
      segmento({ kmInicial: 309, kmFinal: 310 }),
    ]);

    expect(limites[0]).toMatchObject({ km: 309, limite: "INICIO" });
    expect(limites[1]).toMatchObject({ km: 310, limite: "FIM" });
  });

  it("posiciona o limite no quilômetro exato, mesmo fracionário", () => {
    const escala = { inicio: 169.5, fim: 175, decrescente: true };
    const limites = marcosDeLimite(escala, [
      segmento({ kmInicial: 174, kmFinal: 170.5 }),
    ]);

    expect(limites[1].km).toBe(170.5);
    expect(limites[1].posicao).toBeCloseTo(
      ((175 - 170.5) / (175 - 169.5)) * 100,
      5,
    );
  });

  it("não duplica o marcador quando início e fim coincidem", () => {
    const escala = { inicio: 170, fim: 173, decrescente: false };
    const limites = marcosDeLimite(escala, [
      segmento({ kmInicial: 171, kmFinal: 171 }),
    ]);

    expect(limites).toHaveLength(1);
    expect(limites[0].limite).toBe("INICIO");
  });

  it("não marca limite sem segmento posicionável", () => {
    const escala = { inicio: 170, fim: 173, decrescente: false };

    expect(marcosDeLimite(escala, [])).toEqual([]);
  });
});

describe("blocoDoSegmento", () => {
  const escala = { inicio: 170, fim: 174, decrescente: false };

  it("posiciona o bloco pelo intervalo quilométrico real", () => {
    const bloco = blocoDoSegmento(
      segmento({ kmInicial: 171, kmFinal: 172 }),
      escala,
    );

    expect(bloco?.inicio).toBe(25);
    expect(bloco?.largura).toBe(25);
  });

  it("normaliza a ordem dos extremos no sentido decrescente", () => {
    const bloco = blocoDoSegmento(
      segmento({ kmInicial: 172, kmFinal: 171 }),
      escala,
    );

    expect(bloco?.inicio).toBe(25);
    expect(bloco?.largura).toBe(25);
  });

  it("garante largura mínima visível para um trecho pontual", () => {
    const bloco = blocoDoSegmento(
      segmento({ kmInicial: 171, kmFinal: 171 }),
      escala,
    );

    expect(bloco?.largura).toBeGreaterThan(0);
    expect(bloco?.largura).toBeLessThan(2);
  });

  it("não posiciona segmento sem marcação quilométrica", () => {
    expect(blocoDoSegmento(segmento({ kmFinal: null }), escala)).toBeNull();
  });

  it("não deixa o bloco transbordar a régua", () => {
    const bloco = blocoDoSegmento(
      segmento({ kmInicial: 173.9, kmFinal: 180 }),
      escala,
    );

    expect((bloco?.inicio ?? 0) + (bloco?.largura ?? 0)).toBeLessThanOrEqual(100);
  });
});

describe("pistasDoTrecho", () => {
  const escala = { inicio: 170, fim: 174, decrescente: false };

  it("cria uma pista por combinação real de sentido e faixa", () => {
    const pistas = pistasDoTrecho(
      [
        segmento({ id: "a", sentido: "SUL", faixa: "1" }),
        segmento({ id: "b", sentido: "SUL", faixa: "1" }),
        segmento({ id: "c", sentido: "SUL", faixa: "2" }),
        segmento({ id: "d", sentido: "NORTE", faixa: "1" }),
      ],
      escala,
    );

    expect(pistas.map((pista) => pista.id)).toEqual([
      "NORTE::1",
      "SUL::1",
      "SUL::2",
    ]);
    expect(pistas.find((pista) => pista.id === "SUL::1")?.blocos).toHaveLength(2);
  });

  it("cai para a pista quando o sentido não foi declarado", () => {
    const pistas = pistasDoTrecho(
      [segmento({ sentido: null, pista: "Pista sul" })],
      escala,
    );

    expect(pistas[0].sentido).toBe("Pista sul");
  });

  it("rotula honestamente a ausência de sentido e faixa", () => {
    const pistas = pistasDoTrecho(
      [segmento({ sentido: null, pista: null, faixa: null })],
      escala,
    );

    expect(pistas[0].sentido).toBe("Sentido não declarado");
    expect(pistas[0].faixa).toBe("Faixa não declarada");
  });
});

describe("ladosDoCanteiro", () => {
  const escala = { inicio: 170, fim: 174, decrescente: false };

  it("separa os dois sentidos ao redor do canteiro central", () => {
    const pistas = pistasDoTrecho(
      [
        segmento({ id: "a", sentido: "NORTE", faixa: "1" }),
        segmento({ id: "b", sentido: "SUL", faixa: "1" }),
      ],
      escala,
    );

    const lados = ladosDoCanteiro(pistas);

    expect(lados.temCanteiro).toBe(true);
    expect(lados.superior.map((pista) => pista.sentido)).toEqual(["NORTE"]);
    expect(lados.inferior.map((pista) => pista.sentido)).toEqual(["SUL"]);
  });

  it("não desenha canteiro quando a obra tem um único sentido", () => {
    const pistas = pistasDoTrecho(
      [
        segmento({ id: "a", sentido: "SUL", faixa: "1" }),
        segmento({ id: "b", sentido: "SUL", faixa: "2" }),
      ],
      escala,
    );

    const lados = ladosDoCanteiro(pistas);

    expect(lados.temCanteiro).toBe(false);
    expect(lados.superior).toHaveLength(2);
    expect(lados.inferior).toHaveLength(0);
  });
});

describe("estadoDoSegmento", () => {
  it("distingue planejamento, execução, validação e rejeição", () => {
    expect(estadoDoSegmento(segmento({ origem: "PROGRAMACAO" }))).toBe(
      "PROGRAMADO",
    );
    expect(estadoDoSegmento(segmento({ status: "REGISTRADA" }))).toBe(
      "EXECUTADO",
    );
    expect(estadoDoSegmento(segmento({ status: "VALIDADA" }))).toBe("VALIDADO");
    expect(estadoDoSegmento(segmento({ status: "REJEITADA" }))).toBe(
      "REJEITADO",
    );
  });

  it("trata status ausente como execução registrada", () => {
    expect(estadoDoSegmento(segmento({ status: null }))).toBe("EXECUTADO");
  });
});

describe("rótulos", () => {
  it("mostra a extensão apenas quando ela foi medida", () => {
    expect(rotuloDoSegmento(segmento({ extensaoM: 1500 }))).toBe(
      "Recapeamento (1.500 m)",
    );
    expect(rotuloDoSegmento(segmento({ extensaoM: null }))).toBe("Recapeamento");
  });

  it("cai para o subtrecho quando o serviço não tem nome", () => {
    expect(
      rotuloDoSegmento(
        segmento({ servicoNome: null, subtrecho: "Subtrecho 3", extensaoM: null }),
      ),
    ).toBe("Subtrecho 3");
  });

  it("formata quilômetro ausente sem inventar zero", () => {
    expect(formatarKm(null)).toBe("—");
    expect(formatarKm(0)).toBe("km 0");
    expect(formatarKm(309.04)).toBe("km 309,04");
  });
});
