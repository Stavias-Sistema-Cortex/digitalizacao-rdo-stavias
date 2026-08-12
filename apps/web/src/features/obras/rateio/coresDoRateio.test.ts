import { describe, expect, it } from "vitest";

import { CORES_DO_RATEIO, coresPorObra } from "./coresDoRateio";

describe("as cores das obras na matriz", () => {
  it("dá a mesma cor à mesma obra, em qualquer aparelho e em qualquer mês", () => {
    const julho = coresPorObra(["obra-a", "obra-b", "obra-c"]);
    // Agosto trouxe uma obra nova e perdeu outra; as que continuam não podem
    // trocar de cor, ou a leitura de memória do mês passado passa a enganar.
    const agosto = coresPorObra(["obra-c", "obra-a", "obra-d"]);

    expect(agosto.get("obra-a")).toBe(julho.get("obra-a"));
    expect(agosto.get("obra-c")).toBe(julho.get("obra-c"));
  });

  it("não repete cor entre as obras do mesmo retrato", () => {
    const obras = Array.from({ length: CORES_DO_RATEIO }, (_, indice) =>
      `obra-${indice}`,
    );
    const cores = coresPorObra(obras);
    expect(new Set(cores.values()).size).toBe(CORES_DO_RATEIO);
  });

  it("mantém as cores dentro da paleta", () => {
    const cores = coresPorObra(
      Array.from({ length: 40 }, (_, indice) => `obra-${indice}`),
    );
    for (const cor of cores.values()) {
      expect(cor).toBeGreaterThanOrEqual(1);
      expect(cor).toBeLessThanOrEqual(CORES_DO_RATEIO);
    }
  });

  it("não se importa com a ordem em que as obras chegam", () => {
    const direta = coresPorObra(["um", "dois", "tres"]);
    const invertida = coresPorObra(["tres", "dois", "um"]);
    expect([...direta.entries()].sort()).toEqual(
      [...invertida.entries()].sort(),
    );
  });

  it("ignora repetição na entrada", () => {
    const cores = coresPorObra(["obra-a", "obra-a", "obra-b"]);
    expect(cores.size).toBe(2);
  });

  it("não devolve nada quando não há obra", () => {
    expect(coresPorObra([]).size).toBe(0);
  });
});
