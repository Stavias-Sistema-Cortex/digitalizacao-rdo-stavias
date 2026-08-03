import { describe, expect, it, vi } from "vitest";

import { sondarEstilo } from "./sondaDeEstilo";

const ESTILO = "https://tiles.openfreemap.org/styles/liberty";
const MALHA = "https://tiles.openfreemap.org/planet";

function resposta(corpo: unknown, init: Partial<Response> = {}): Response {
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    type: init.type ?? "basic",
    json: () => Promise.resolve(corpo),
  } as Response;
}

describe("sondarEstilo", () => {
  it("aprova o estilo cujas fontes declaradas também respondem", async () => {
    const buscar = vi.fn(async (entrada: RequestInfo | URL) =>
      String(entrada) === ESTILO
        ? resposta({ version: 8, sources: { malha: { url: MALHA } } })
        : resposta({ tiles: ["https://tiles.openfreemap.org/{z}/{x}/{y}.pbf"] }),
    );

    await expect(
      sondarEstilo(ESTILO, { buscar: buscar as unknown as typeof fetch }),
    ).resolves.toEqual({ usavel: true, motivo: null });
    expect(buscar).toHaveBeenCalledTimes(2);
  });

  it("reprova o estilo que o servidor recusa", async () => {
    // Serviço aberto sob carga responde e nega. Antes isto passava direto: o
    // mapa montava, se declarava pronto e não desenhava nada.
    const buscar = vi.fn(async () =>
      resposta(null, { ok: false, status: 403 }),
    );

    const resultado = await sondarEstilo(ESTILO, {
      buscar: buscar as unknown as typeof fetch,
    });
    expect(resultado.usavel).toBe(false);
    expect(resultado.motivo).toContain("403");
  });

  it("reprova quando o estilo chega e a malha declarada não", async () => {
    // O modo mais traiçoeiro: o JSON do estilo vem, e a fonte que ele aponta
    // não. O resultado é um fundo liso — o retângulo mudo visto em campo.
    const buscar = vi.fn(async (entrada: RequestInfo | URL) => {
      if (String(entrada) === ESTILO) {
        return resposta({ version: 8, sources: { malha: { url: MALHA } } });
      }
      throw new TypeError("Failed to fetch");
    });

    const resultado = await sondarEstilo(ESTILO, {
      buscar: buscar as unknown as typeof fetch,
    });
    expect(resultado.usavel).toBe(false);
    expect(resultado.motivo).toContain("malha");
  });

  it("reprova a resposta opaca, que o renderizador não consegue ler", async () => {
    const buscar = vi.fn(async () => resposta(null, { type: "opaque" }));

    const resultado = await sondarEstilo(ESTILO, {
      buscar: buscar as unknown as typeof fetch,
    });
    expect(resultado.usavel).toBe(false);
    expect(resultado.motivo).toContain("opaca");
  });

  it("reprova o servidor que não responde dentro do limite", async () => {
    const buscar = vi.fn(
      (_entrada: RequestInfo | URL, init?: RequestInit) =>
        new Promise<Response>((_, rejeitar) => {
          init?.signal?.addEventListener("abort", () =>
            rejeitar(new DOMException("Aborted", "AbortError")),
          );
        }),
    );

    const resultado = await sondarEstilo(ESTILO, {
      buscar: buscar as unknown as typeof fetch,
      limiteMs: 5,
    });
    expect(resultado.usavel).toBe(false);
    expect(resultado.motivo).toContain("não respondeu a tempo");
  });

  it("aprova o estilo embutido, que não tem host a consultar", async () => {
    const buscar = vi.fn();

    await expect(
      sondarEstilo(null, { buscar: buscar as unknown as typeof fetch }),
    ).resolves.toEqual({ usavel: true, motivo: null });
    expect(buscar).not.toHaveBeenCalled();
  });

  it("não reprova o estilo que só o runtime do provider sabe resolver", async () => {
    // `mapbox://` não é endereço de rede: nenhum `fetch` o alcança, e sondá-lo
    // derrubaria para o basemap base um provider pago que está funcionando.
    const buscar = vi.fn();

    await expect(
      sondarEstilo("mapbox://styles/mapbox/satellite-streets-v12", {
        buscar: buscar as unknown as typeof fetch,
      }),
    ).resolves.toEqual({ usavel: true, motivo: null });
    expect(buscar).not.toHaveBeenCalled();
  });
});
