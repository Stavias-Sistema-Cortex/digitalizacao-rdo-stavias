import { describe, expect, it } from "vitest";

import { colaboradorStorageKey } from "./lastAccessedObra";

describe("colaboradorStorageKey", () => {
  it("usa colaboradorId quando existe", () => {
    expect(
      colaboradorStorageKey({
        colaboradorId: "colab-1",
      }),
    ).toBe("cortex.home.ultimaObra:colab-1");
  });

  it("não cria chave por dado pessoal quando o id está ausente", () => {
    expect(
      colaboradorStorageKey({
        colaboradorId: null,
      }),
    ).toBeNull();
  });

  it("retorna null sem sessão", () => {
    expect(colaboradorStorageKey(null)).toBeNull();
  });
});
