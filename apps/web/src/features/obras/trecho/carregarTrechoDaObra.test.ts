import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiTransportError } from "../../../lib/api/apiClient";
import type { SegmentoTrecho } from "./trechoGeometry";

const apiFetch = vi.hoisted(() => vi.fn());
const lerTrechoEmCache = vi.hoisted(() => vi.fn());
const gravarTrechoEmCache = vi.hoisted(() => vi.fn());
const lancamentosLocaisDaObra = vi.hoisted(() => vi.fn());

vi.mock("../../../lib/api/apiClient", async () => {
  const real = await vi.importActual<
    typeof import("../../../lib/api/apiClient")
  >("../../../lib/api/apiClient");
  return { ...real, apiFetch };
});

vi.mock("../map/obraGeoCacheRepository", () => ({
  lerTrechoEmCache,
  gravarTrechoEmCache,
}));

vi.mock("./trechoLocal", () => ({ lancamentosLocaisDaObra }));

const { carregarTrechoDaObra } = await import("./obraTrechoApi");

const OBRA = {
  id: "obra-1",
  nome: "Recapeamento SP-310",
  operavel: true,
};

const respostaDoServidor = {
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
  segmentos: [
    {
      id: "ctrl-1",
      origem: "RDO_CONTROLE",
      rdoId: "rdo-1",
      numeroRdo: "RDO-1",
      data: "2026-03-02",
      servicoNome: "Recapeamento",
      pista: "SUL",
      faixa: "1",
      kmInicial: 172,
      kmFinal: 171,
      extensaoM: 1000,
      status: "VALIDADA",
      rdoStatus: "ENVIADO",
    },
  ],
  diasExecutados: [],
  resumo: {
    totalSegmentos: 1,
    totalRdos: 1,
    totalRascunhos: 0,
    extensaoTotalM: 1000,
    areaTotalM2: 0,
    massaTotalTonelada: 0,
    primeiraExecucao: "2026-03-02",
    ultimaExecucao: "2026-03-02",
  },
};

function segmentoLocal(
  overrides: Partial<SegmentoTrecho> = {},
): SegmentoTrecho {
  return {
    id: "local:rdo-9:controle:c1",
    origem: "RDO_CONTROLE",
    rdoId: "rdo-9",
    numeroRdo: "RDO-9",
    data: "2026-03-09",
    servicoNome: "Recapeamento",
    subtrecho: null,
    sentido: null,
    pista: "SUL",
    faixa: "1",
    kmInicial: 176,
    kmFinal: 174,
    estacaInicial: null,
    estacaFinal: null,
    extensaoM: 2000,
    larguraM: null,
    areaM2: null,
    massaTonelada: null,
    status: "RASCUNHO",
    rdoStatus: "RASCUNHO",
    procedencia: "DISPOSITIVO",
    pistaInferida: false,
    ...overrides,
  };
}

function resposta(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ "content-type": "application/json" }),
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response;
}

beforeEach(() => {
  apiFetch.mockReset();
  lerTrechoEmCache.mockReset().mockResolvedValue(null);
  gravarTrechoEmCache.mockReset().mockResolvedValue(undefined);
  lancamentosLocaisDaObra
    .mockReset()
    .mockResolvedValue({ segmentos: [], rodovia: null });
});

describe("carregarTrechoDaObra", () => {
  it("soma à projeção do servidor o RDO lançado neste aparelho", async () => {
    apiFetch.mockResolvedValue(resposta(200, respostaDoServidor));
    lancamentosLocaisDaObra.mockResolvedValue({
      segmentos: [segmentoLocal()],
      rodovia: "SP-310",
    });

    const leitura = await carregarTrechoDaObra(OBRA);

    expect(leitura.origem).toBe("REDE");
    expect(leitura.projecao.segmentos).toHaveLength(2);
    expect(leitura.projecao.resumo.totalPendentes).toBe(1);
    // O consolidado continua sendo apenas o que o servidor confirmou.
    expect(leitura.projecao.resumo.extensaoTotalM).toBe(1000);
  });

  it("não duplica o RDO que o servidor já projetou", async () => {
    apiFetch.mockResolvedValue(resposta(200, respostaDoServidor));
    lancamentosLocaisDaObra.mockResolvedValue({
      segmentos: [segmentoLocal({ rdoId: "rdo-1" })],
      rodovia: "SP-310",
    });

    const leitura = await carregarTrechoDaObra(OBRA);

    expect(leitura.projecao.segmentos).toHaveLength(1);
    expect(leitura.projecao.resumo.totalPendentes).toBe(0);
  });

  it("apresenta só o aparelho quando não há rede nem cache", async () => {
    apiFetch.mockRejectedValue(
      new ApiTransportError("Sem conexão.", "CONNECTION"),
    );
    lancamentosLocaisDaObra.mockResolvedValue({
      segmentos: [segmentoLocal()],
      rodovia: "SP-225",
    });

    const leitura = await carregarTrechoDaObra(OBRA);

    expect(leitura.origem).toBe("DISPOSITIVO");
    expect(leitura.projecao.rodovia).toBe("SP-225");
    expect(leitura.projecao.segmentos).toHaveLength(1);
    expect(leitura.projecao.resumo.totalPendentes).toBe(1);
    expect(leitura.projecao.resumo.extensaoTotalM).toBe(0);
  });

  it("propaga o erro do servidor mesmo havendo lançamento local", async () => {
    apiFetch.mockResolvedValue(
      resposta(403, { message: "Sem acesso à obra." }),
    );
    lancamentosLocaisDaObra.mockResolvedValue({
      segmentos: [segmentoLocal()],
      rodovia: "SP-310",
    });

    await expect(carregarTrechoDaObra(OBRA)).rejects.toThrow(
      /Sem acesso à obra/,
    );
  });

  it("sobe o erro quando não há rede, cache nem lançamento local", async () => {
    apiFetch.mockRejectedValue(
      new ApiTransportError("Sem conexão.", "CONNECTION"),
    );

    await expect(carregarTrechoDaObra(OBRA)).rejects.toThrow(/Sem conexão/);
  });
});
