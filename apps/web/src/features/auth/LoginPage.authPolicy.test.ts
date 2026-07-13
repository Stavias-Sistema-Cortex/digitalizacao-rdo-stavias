import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

describe("LoginPage auth policy", () => {
  it("não anuncia nem consulta autenticação offline por Bloom", () => {
    const source = readFileSync(
      new URL("./LoginPage.tsx", import.meta.url),
      "utf8",
    );

    expect(source).not.toContain("getCachedFilter");
    expect(source).not.toContain("filtroOfflinePronto");
    expect(source).not.toContain("login offline está habilitado");
    expect(source).toContain("O login exige conexão com o Córtex.");
  });
});
