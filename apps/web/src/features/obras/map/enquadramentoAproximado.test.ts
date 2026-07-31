// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  buscarEnquadramentoAproximado,
  consultaDoEndereco,
  enquadramentoDaResposta,
} from "./enquadramentoAproximado";

const RESPOSTA = [
  {
    lat: "-22.4148",
    lon: "-47.5613",
    display_name: "Rio Claro, Região Imediata de Rio Claro, SP, Brasil",
    boundingbox: ["-22.5", "-22.3", "-47.7", "-47.4"],
  },
];

function fetchQueRetorna(corpo: unknown, ok = true): typeof fetch {
  return vi.fn(() =>
    Promise.resolve({ ok, json: () => Promise.resolve(corpo) } as Response),
  ) as unknown as typeof fetch;
}

beforeEach(() => {
  sessionStorage.clear();
});

describe("consultaDoEndereco", () => {
  it("combina rodovia, cidade e UF", () => {
    expect(
      consultaDoEndereco({ cidade: "Rio Claro", uf: "SP", rodovia: "SP-310" }),
    ).toBe("SP-310, Rio Claro, SP, Brasil");
  });

  it("dispensa a rodovia quando ela não foi cadastrada", () => {
    expect(
      consultaDoEndereco({ cidade: "Rio Claro", uf: "SP", rodovia: null }),
    ).toBe("Rio Claro, SP, Brasil");
  });

  it("não consulta sem cidade, porque a rodovia atravessa o estado inteiro", () => {
    expect(
      consultaDoEndereco({ cidade: null, uf: "SP", rodovia: "SP-310" }),
    ).toBeNull();
    expect(
      consultaDoEndereco({ cidade: "   ", uf: "SP", rodovia: "SP-310" }),
    ).toBeNull();
  });
});

describe("enquadramentoDaResposta", () => {
  it("lê centro, limites e o nome reconhecido", () => {
    const enquadramento = enquadramentoDaResposta(RESPOSTA, "consulta");

    expect(enquadramento?.centro).toEqual([-47.5613, -22.4148]);
    expect(enquadramento?.limites).toEqual([
      [-47.7, -22.5],
      [-47.4, -22.3],
    ]);
    expect(enquadramento?.local).toBe("Rio Claro, Região Imediata de Rio Claro");
  });

  it("recusa coordenada fora do intervalo geográfico", () => {
    expect(
      enquadramentoDaResposta([{ lat: "999", lon: "0" }], "consulta"),
    ).toBeNull();
  });

  it("devolve nulo quando o serviço não reconhece o endereço", () => {
    expect(enquadramentoDaResposta([], "consulta")).toBeNull();
    expect(enquadramentoDaResposta(null, "consulta")).toBeNull();
  });

  it("tolera resposta sem caixa delimitadora", () => {
    const enquadramento = enquadramentoDaResposta(
      [{ lat: "-22.4", lon: "-47.5" }],
      "consulta",
    );

    expect(enquadramento?.centro).toEqual([-47.5, -22.4]);
    expect(enquadramento?.limites).toBeNull();
  });
});

describe("buscarEnquadramentoAproximado", () => {
  const endereco = { cidade: "Rio Claro", uf: "SP", rodovia: "SP-310" };

  it("consulta o serviço sem enviar sessão do Córtex", async () => {
    const fetchImpl = fetchQueRetorna(RESPOSTA);

    const enquadramento = await buscarEnquadramentoAproximado(
      endereco,
      fetchImpl,
    );

    expect(enquadramento?.centro).toEqual([-47.5613, -22.4148]);
    const [, opcoes] = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock
      .calls[0];
    expect(opcoes.credentials).toBe("omit");
  });

  it("reaproveita a consulta na mesma sessão em vez de repetir a chamada", async () => {
    const fetchImpl = fetchQueRetorna(RESPOSTA);

    await buscarEnquadramentoAproximado(endereco, fetchImpl);
    await buscarEnquadramentoAproximado(endereco, fetchImpl);

    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("não consulta nada quando a obra não declara cidade", async () => {
    const fetchImpl = fetchQueRetorna(RESPOSTA);

    const enquadramento = await buscarEnquadramentoAproximado(
      { cidade: null, uf: "SP", rodovia: "SP-310" },
      fetchImpl,
    );

    expect(enquadramento).toBeNull();
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("falha em silêncio quando o serviço recusa ou a rede cai", async () => {
    await expect(
      buscarEnquadramentoAproximado(endereco, fetchQueRetorna(RESPOSTA, false)),
    ).resolves.toBeNull();

    const semRede = vi.fn(() =>
      Promise.reject(new Error("offline")),
    ) as unknown as typeof fetch;
    await expect(
      buscarEnquadramentoAproximado(endereco, semRede),
    ).resolves.toBeNull();
  });
});
