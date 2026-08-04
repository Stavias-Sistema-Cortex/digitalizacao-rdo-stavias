import { describe, expect, it } from "vitest";

import {
  blocoDoSegmento,
  calcularEscalaKm,
  estadoDoSegmento,
  formatarKm,
  herdarPistaDoControle,
  ladosDoCanteiro,
  marcosDeLimite,
  marcosKm,
  passoDaRegua,
  pistasDoTrecho,
  mesclarLancamentosLocais,
  posicaoPercentual,
  projecaoDoDispositivo,
  recortarProjecao,
  rotuloDoSegmento,
  segmentosPosicionaveis,
  type ProjecaoTrecho,
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
    rdoStatus: "ENVIADO",
    procedencia: "SERVIDOR",
    pistaInferida: false,
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

  /**
   * A régua emitia um rótulo por quilômetro, sempre. Num trecho curto isso é
   * o certo; com o filtro em "todo o período", que abre a escala de um extremo
   * da obra ao outro, viravam dezenas de números impressos uns sobre os
   * outros — um borrão que não diz nem onde a obra começa nem onde termina.
   */
  it("espaça os marcos quando o vão é largo demais para um por quilômetro", () => {
    const escala = { inicio: 100, fim: 172, decrescente: true };

    const marcos = marcosKm(escala).map((marco) => marco.km);

    expect(marcos.length).toBeLessThanOrEqual(20);
    // Números redondos: quem lê a régua lê quilometragem, não sobra de
    // divisão. Com passo 5, a escala que começa em 100 marca 100, 105, 110.
    expect(marcos.every((km) => km % 5 === 0)).toBe(true);
    expect(marcos[0]).toBe(170);
    expect(marcos.at(-1)).toBe(100);
  });

  it("mantém um marco por quilômetro enquanto eles cabem", () => {
    expect(
      marcosKm({ inicio: 170, fim: 180, decrescente: false }).map(
        (marco) => marco.km,
      ),
    ).toEqual([170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180]);
  });
});

describe("passoDaRegua", () => {
  it("cresce com o vão, em passos que se leem como quilometragem", () => {
    expect(passoDaRegua(10)).toBe(1);
    expect(passoDaRegua(72)).toBe(5);
    expect(passoDaRegua(300)).toBe(20);
  });

  /** Vão inválido não pode gerar passo zero: o laço nunca terminaria. */
  it("nunca devolve passo que trave a régua", () => {
    expect(passoDaRegua(0)).toBe(1);
    expect(passoDaRegua(Number.NaN)).toBe(1);
    expect(passoDaRegua(-5)).toBe(1);
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
    expect(lados.indefinidas).toHaveLength(0);
  });

  it("mantém o canteiro quando sobra um lançamento sem sentido declarado", () => {
    const pistas = pistasDoTrecho(
      [
        segmento({ id: "a", sentido: "NORTE", faixa: "1" }),
        segmento({ id: "b", sentido: "SUL", faixa: "1" }),
        segmento({
          id: "c",
          origem: "EXECUCAO_SERVICO",
          sentido: null,
          pista: null,
          faixa: null,
        }),
      ],
      escala,
    );

    const lados = ladosDoCanteiro(pistas);

    // A pista sem sentido não some nem escolhe um lado: ela sai do par.
    expect(lados.temCanteiro).toBe(true);
    expect(lados.superior.map((pista) => pista.sentido)).toEqual(["NORTE"]);
    expect(lados.inferior.map((pista) => pista.sentido)).toEqual(["SUL"]);
    expect(lados.indefinidas).toHaveLength(1);
  });

  it("não perde as pistas indefinidas quando não há canteiro", () => {
    const pistas = pistasDoTrecho(
      [
        segmento({ id: "a", sentido: "SUL", faixa: "1" }),
        segmento({
          id: "b",
          origem: "EXECUCAO_SERVICO",
          sentido: null,
          pista: null,
          faixa: null,
        }),
      ],
      escala,
    );

    const lados = ladosDoCanteiro(pistas);

    expect(lados.temCanteiro).toBe(false);
    expect(
      lados.superior.length + lados.indefinidas.length,
    ).toBe(pistas.length);
  });
});

describe("herdarPistaDoControle", () => {
  function servicoSemPista(
    overrides: Partial<SegmentoTrecho> = {},
  ): SegmentoTrecho {
    return segmento({
      id: "exec-1",
      origem: "EXECUCAO_SERVICO",
      sentido: null,
      pista: null,
      faixa: null,
      kmInicial: 171.2,
      kmFinal: 171.8,
      ...overrides,
    });
  }

  it("herda a pista do controle que encosta nos quilômetros do serviço", () => {
    const resultado = herdarPistaDoControle([
      segmento({ id: "ctrl-1", pista: "NORTE", faixa: "1", kmInicial: 171, kmFinal: 172 }),
      segmento({ id: "ctrl-2", pista: "SUL", faixa: "2", kmInicial: 175, kmFinal: 176 }),
      servicoSemPista(),
    ]);

    const herdado = resultado.find((s) => s.id === "exec-1");
    expect(herdado?.pista).toBe("NORTE");
    expect(herdado?.faixa).toBe("1");
    expect(herdado?.pistaInferida).toBe(true);
  });

  it("cai no consenso do RDO quando o serviço não tem quilômetros", () => {
    const resultado = herdarPistaDoControle([
      segmento({ id: "ctrl-1", pista: "NORTE", faixa: "1" }),
      segmento({ id: "ctrl-2", pista: "NORTE", faixa: "2" }),
      servicoSemPista({ kmInicial: null, kmFinal: null }),
    ]);

    const herdado = resultado.find((s) => s.id === "exec-1");
    // A pista é unânime; a faixa diverge e fica ausente em vez de virar palpite.
    expect(herdado?.pista).toBe("NORTE");
    expect(herdado?.faixa).toBeNull();
    expect(herdado?.pistaInferida).toBe(true);
  });

  it("não arrisca pista quando os controles divergem", () => {
    const resultado = herdarPistaDoControle([
      segmento({ id: "ctrl-1", pista: "NORTE", kmInicial: 171, kmFinal: 172 }),
      segmento({ id: "ctrl-2", pista: "SUL", kmInicial: 171, kmFinal: 172 }),
      servicoSemPista(),
    ]);

    const intocado = resultado.find((s) => s.id === "exec-1");
    expect(intocado?.pista).toBeNull();
    expect(intocado?.pistaInferida).toBe(false);
  });

  it("nunca empresta pista de controle de outro RDO", () => {
    const resultado = herdarPistaDoControle([
      segmento({ id: "ctrl-1", rdoId: "rdo-OUTRO", pista: "NORTE" }),
      servicoSemPista(),
    ]);

    expect(resultado.find((s) => s.id === "exec-1")?.pista).toBeNull();
  });

  it("respeita a pista que o próprio serviço declarou", () => {
    const resultado = herdarPistaDoControle([
      segmento({ id: "ctrl-1", pista: "NORTE", kmInicial: 171, kmFinal: 172 }),
      servicoSemPista({ pista: "SUL" }),
    ]);

    const declarado = resultado.find((s) => s.id === "exec-1");
    expect(declarado?.pista).toBe("SUL");
    expect(declarado?.pistaInferida).toBe(false);
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

function projecao(
  segmentos: SegmentoTrecho[],
  overrides: Partial<ProjecaoTrecho> = {},
): ProjecaoTrecho {
  return {
    obraId: "obra-1",
    obraNome: "Recapeamento SP-310",
    rodovia: "SP-310",
    operavel: true,
    sentidos: ["SUL"],
    faixas: ["1"],
    kmMin: 171,
    kmMax: 172,
    atualizadoEm: "2026-03-02T18:30:00",
    periodoDisponivel: { de: "2026-03-02", ate: "2026-03-02" },
    periodoAplicado: { de: null, ate: null },
    segmentos,
    diasExecutados: [
      {
        data: "2026-03-02",
        totalSegmentos: 1,
        totalRdos: 1,
        extensaoM: 1000,
        massaTonelada: 0,
        kmMin: 171,
        kmMax: 172,
      },
    ],
    resumo: {
      totalSegmentos: segmentos.length,
      totalRdos: 1,
      totalRascunhos: 0,
      totalPendentes: 0,
      extensaoTotalM: 1000,
      areaTotalM2: 0,
      massaTotalTonelada: 0,
      primeiraExecucao: "2026-03-02",
      ultimaExecucao: "2026-03-02",
    },
    ...overrides,
  };
}

function local(overrides: Partial<SegmentoTrecho> = {}): SegmentoTrecho {
  return segmento({
    id: "local:rdo-9:controle:c1",
    rdoId: "rdo-9",
    numeroRdo: "RDO-9",
    data: "2026-03-09",
    kmInicial: 176,
    kmFinal: 174,
    extensaoM: 2000,
    status: "RASCUNHO",
    rdoStatus: "RASCUNHO",
    procedencia: "DISPOSITIVO",
    pistaInferida: false,
    ...overrides,
  });
}

describe("estadoDoSegmento com lançamento local", () => {
  it("marca como pendente o que só existe neste aparelho", () => {
    expect(estadoDoSegmento(local())).toBe("PENDENTE");
  });

  it("mantém a precedência sobre rascunho e sobre a validação do serviço", () => {
    expect(
      estadoDoSegmento(local({ rdoStatus: "ENVIADO", status: "VALIDADA" })),
    ).toBe("PENDENTE");
  });
});

describe("mesclarLancamentosLocais", () => {
  it("desenha o RDO do dia antes de ele subir", () => {
    const mesclada = mesclarLancamentosLocais(projecao([segmento()]), [local()]);

    expect(mesclada.segmentos).toHaveLength(2);
    expect(mesclada.resumo.totalPendentes).toBe(1);
    // A régua precisa alcançar o quilômetro lançado agora.
    expect(mesclada.kmMax).toBe(176);
    expect(mesclada.kmMin).toBe(171);
  });

  it("não soma o lançamento pendente ao consolidado", () => {
    const mesclada = mesclarLancamentosLocais(projecao([segmento()]), [local()]);

    expect(mesclada.resumo.extensaoTotalM).toBe(1000);
    expect(mesclada.resumo.totalRdos).toBe(1);
  });

  it("abre no calendário o dia que ainda não sincronizou", () => {
    const mesclada = mesclarLancamentosLocais(projecao([segmento()]), [local()]);

    expect(mesclada.diasExecutados.map((dia) => dia.data)).toEqual([
      "2026-03-02",
      "2026-03-09",
    ]);
    expect(mesclada.periodoDisponivel).toEqual({
      de: "2026-03-02",
      ate: "2026-03-09",
    });
  });

  it("descarta o lançamento local do RDO que o servidor já projetou", () => {
    const mesclada = mesclarLancamentosLocais(
      projecao([segmento({ rdoId: "rdo-9" })]),
      [local()],
    );

    expect(mesclada.segmentos).toHaveLength(1);
    expect(mesclada.resumo.totalPendentes).toBe(0);
  });

  it("devolve a projeção intocada quando nada há de local", () => {
    const original = projecao([segmento()]);
    expect(mesclarLancamentosLocais(original, [])).toBe(original);
  });
});

describe("recortarProjecao com lançamento local", () => {
  it("mantém o pendente visível no dia dele e fora do consolidado", () => {
    const mesclada = mesclarLancamentosLocais(projecao([segmento()]), [local()]);
    const recorte = recortarProjecao(mesclada, {
      de: "2026-03-09",
      ate: "2026-03-09",
    });

    expect(recorte.segmentos).toHaveLength(1);
    expect(recorte.resumo.totalPendentes).toBe(1);
    expect(recorte.resumo.extensaoTotalM).toBe(0);
    expect(recorte.resumo.totalRdos).toBe(0);
  });
});

describe("projecaoDoDispositivo", () => {
  it("monta a projeção do aparelho quando nem rede nem cache respondem", () => {
    const derivada = projecaoDoDispositivo(
      { id: "obra-1", nome: "Recapeamento SP-310", operavel: true },
      [local()],
      "SP-310",
    );

    expect(derivada.rodovia).toBe("SP-310");
    expect(derivada.kmMin).toBe(174);
    expect(derivada.kmMax).toBe(176);
    expect(derivada.resumo.totalPendentes).toBe(1);
    // Nada foi confirmado pelo servidor: o consolidado é zero, não a produção.
    expect(derivada.resumo.extensaoTotalM).toBe(0);
    expect(derivada.resumo.totalRdos).toBe(0);
    expect(derivada.diasExecutados.map((dia) => dia.data)).toEqual([
      "2026-03-09",
    ]);
  });
});
