import { describe, expect, it } from "vitest";

import { trechoResponseFromApi } from "./obraTrechoApi";
import { recortarProjecao } from "./trechoGeometry";

const base = {
  obraId: "obra-1",
  obraNome: "Recapeamento SP-310",
  rodovia: "SP-310",
  operavel: true,
  sentidos: ["SUL"],
  faixas: ["1"],
  kmMin: 171,
  kmMax: 172,
  atualizadoEm: "2026-03-03T18:30:00",
  periodoDisponivel: { de: "2026-03-02", ate: "2026-03-02" },
  periodoAplicado: { de: null, ate: null },
  segmentos: [],
  diasExecutados: [],
  resumo: {
    totalSegmentos: 0,
    totalRdos: 0,
    extensaoTotalM: 0,
    areaTotalM2: 0,
    massaTotalTonelada: 0,
    primeiraExecucao: null,
    ultimaExecucao: null,
  },
};

describe("trechoResponseFromApi", () => {
  it("lê a projeção autoritativa completa", () => {
    const projecao = trechoResponseFromApi({
      ...base,
      segmentos: [
        {
          id: "ctrl-1",
          origem: "RDO_CONTROLE",
          rdoId: "rdo-1",
          numeroRdo: "RDO-1",
          data: "2026-03-02",
          servicoNome: "Recapeamento",
          sentido: "SUL",
          faixa: "1",
          kmInicial: 172,
          kmFinal: 171,
          extensaoM: 1500,
          massaTonelada: 380,
          status: "VALIDADA",
        },
      ],
    });

    expect(projecao.obraId).toBe("obra-1");
    expect(projecao.rodovia).toBe("SP-310");
    expect(projecao.operavel).toBe(true);
    expect(projecao.segmentos).toHaveLength(1);
    expect(projecao.segmentos[0].kmInicial).toBe(172);
    expect(projecao.segmentos[0].extensaoM).toBe(1500);
  });

  it("nunca transforma quilômetro ou extensão ausente em zero", () => {
    const projecao = trechoResponseFromApi({
      ...base,
      kmMin: null,
      kmMax: null,
      segmentos: [
        {
          id: "exec-1",
          origem: "EXECUCAO_SERVICO",
          servicoNome: "Sinalização",
          kmInicial: null,
          kmFinal: null,
          extensaoM: null,
        },
      ],
    });

    expect(projecao.kmMin).toBeNull();
    expect(projecao.kmMax).toBeNull();
    expect(projecao.segmentos[0].kmInicial).toBeNull();
    expect(projecao.segmentos[0].extensaoM).toBeNull();
  });

  it("preserva o quilômetro zero, que é uma marcação real", () => {
    const projecao = trechoResponseFromApi({
      ...base,
      segmentos: [
        {
          id: "ctrl-0",
          origem: "RDO_CONTROLE",
          kmInicial: 0,
          kmFinal: 0.5,
        },
      ],
    });

    expect(projecao.segmentos[0].kmInicial).toBe(0);
  });

  it("descarta segmento sem identidade ou com origem desconhecida", () => {
    const projecao = trechoResponseFromApi({
      ...base,
      segmentos: [
        { origem: "RDO_CONTROLE" },
        { id: "x", origem: "ORIGEM_INVENTADA" },
        { id: "ok", origem: "PROGRAMACAO" },
      ],
    });

    expect(projecao.segmentos.map((item) => item.id)).toEqual(["ok"]);
  });

  it("trata operavel ausente como obra não operável", () => {
    const projecao = trechoResponseFromApi({ ...base, operavel: undefined });

    expect(projecao.operavel).toBe(false);
  });

  it("recusa uma resposta que não identifica a obra", () => {
    expect(() => trechoResponseFromApi({ ...base, obraId: null })).toThrow(
      /não identifica a obra/,
    );
    expect(() => trechoResponseFromApi(null)).toThrow(/não identifica a obra/);
  });

  it("tolera listas e resumo ausentes sem quebrar a tela", () => {
    const projecao = trechoResponseFromApi({
      obraId: "obra-1",
      obraNome: "Obra sem produção",
    });

    expect(projecao.segmentos).toEqual([]);
    expect(projecao.sentidos).toEqual([]);
    expect(projecao.resumo.totalSegmentos).toBe(0);
    expect(projecao.atualizadoEm).toBeNull();
  });
});

describe("recortarProjecao", () => {
  const completa = trechoResponseFromApi({
    ...base,
    periodoDisponivel: { de: "2026-03-02", ate: "2026-03-10" },
    segmentos: [
      {
        id: "plan",
        origem: "PROGRAMACAO",
        data: "2026-03-01",
        kmInicial: 174,
        kmFinal: 171,
        extensaoM: 3000,
      },
      {
        id: "dia-2",
        origem: "RDO_CONTROLE",
        rdoId: "rdo-1",
        data: "2026-03-02",
        kmInicial: 172,
        kmFinal: 171,
        extensaoM: 1000,
        massaTonelada: 250,
      },
      {
        id: "dia-10",
        origem: "RDO_CONTROLE",
        rdoId: "rdo-2",
        data: "2026-03-10",
        kmInicial: 171,
        kmFinal: 170,
        extensaoM: 500,
        massaTonelada: 120,
      },
      { id: "sem-data", origem: "RDO_CONTROLE", data: null, kmInicial: 170 },
    ],
    diasExecutados: [
      {
        data: "2026-03-02",
        totalSegmentos: 1,
        totalRdos: 1,
        extensaoM: 1000,
        massaTonelada: 250,
        kmMin: 171,
        kmMax: 172,
      },
      {
        data: "2026-03-10",
        totalSegmentos: 1,
        totalRdos: 1,
        extensaoM: 500,
        massaTonelada: 120,
        kmMin: 170,
        kmMax: 171,
      },
    ],
  });

  it("devolve a projeção intacta sem período escolhido", () => {
    expect(recortarProjecao(completa, { de: null, ate: null })).toBe(completa);
  });

  it("mantém somente o que foi executado dentro do período", () => {
    const recorte = recortarProjecao(completa, {
      de: "2026-03-01",
      ate: "2026-03-05",
    });

    expect(recorte.segmentos.map((item) => item.id)).toEqual(["plan", "dia-2"]);
    expect(recorte.diasExecutados.map((dia) => dia.data)).toEqual([
      "2026-03-02",
    ]);
    expect(recorte.periodoAplicado).toEqual({
      de: "2026-03-01",
      ate: "2026-03-05",
    });
  });

  it("recalcula o resumo apenas com a produção do recorte", () => {
    const recorte = recortarProjecao(completa, {
      de: "2026-03-02",
      ate: "2026-03-02",
    });

    expect(recorte.resumo.extensaoTotalM).toBe(1000);
    expect(recorte.resumo.massaTotalTonelada).toBe(250);
    expect(recorte.resumo.totalRdos).toBe(1);
    expect(recorte.resumo.primeiraExecucao).toBe("2026-03-02");
    expect(recorte.resumo.ultimaExecucao).toBe("2026-03-02");
  });

  it("descarta lançamento sem data quando há período", () => {
    const recorte = recortarProjecao(completa, {
      de: "2026-03-01",
      ate: "2026-03-31",
    });

    expect(recorte.segmentos.some((item) => item.id === "sem-data")).toBe(false);
  });

  it("preserva o período disponível para o seletor de datas", () => {
    const recorte = recortarProjecao(completa, {
      de: "2026-03-02",
      ate: "2026-03-02",
    });

    expect(recorte.periodoDisponivel).toEqual({
      de: "2026-03-02",
      ate: "2026-03-10",
    });
  });
});
