import { describe, expect, it } from "vitest";

import { colaboradorStorageKey } from "./lastAccessedObra";

describe("colaboradorStorageKey", () => {
  it("usa colaboradorId quando existe", () => {
    expect(
      colaboradorStorageKey({
        colaboradorId: "colab-1",
        cpfMascarado: "***.456.789-**",
      }),
    ).toBe("cortex.home.ultimaObra:colab-1");
  });

  it("cai para o cpf mascarado em sessão offline", () => {
    expect(
      colaboradorStorageKey({
        colaboradorId: null,
        cpfMascarado: "***.456.789-**",
      }),
    ).toBe("cortex.home.ultimaObra:***.456.789-**");
  });

  it("retorna null sem sessão", () => {
    expect(colaboradorStorageKey(null)).toBeNull();
  });
});
