import { describe, expect, it } from "vitest";

import { resolveCortexAuthMode } from "./cortexAuthMode";

describe("resolveCortexAuthMode", () => {
  it("usa o acesso PostgreSQL do Cortex 3 em desenvolvimento sem configuração", () => {
    expect(resolveCortexAuthMode({ DEV: true })).toBe("postgresql");
  });

  it("aceita somente os dois modos explícitos", () => {
    expect(
      resolveCortexAuthMode({
        DEV: false,
        PROD: true,
        VITE_CORTEX_AUTH_MODE: "postgresql",
      }),
    ).toBe("postgresql");

    expect(
      resolveCortexAuthMode({
        DEV: false,
        PROD: true,
        VITE_CORTEX_AUTH_MODE: "legacy",
      }),
    ).toBe("legacy");
  });

  it("recusa modo ausente ou desconhecido em produção", () => {
    expect(() => resolveCortexAuthMode({ PROD: true })).toThrow(
      "VITE_CORTEX_AUTH_MODE",
    );
    expect(() =>
      resolveCortexAuthMode({
        PROD: true,
        VITE_CORTEX_AUTH_MODE: "cpf",
      }),
    ).toThrow("VITE_CORTEX_AUTH_MODE");
  });
});
