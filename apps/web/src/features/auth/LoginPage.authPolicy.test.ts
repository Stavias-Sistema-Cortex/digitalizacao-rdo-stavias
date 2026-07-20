import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

describe("LoginPage auth policy", () => {
  it("usa CPF direto e mantém a passkey como ação minimalista", () => {
    const source = readFileSync(
      new URL("./LoginPage.tsx", import.meta.url),
      "utf8",
    );

    expect(source).not.toContain("getCachedFilter");
    expect(source).not.toContain("filtroOfflinePronto");
    expect(source).not.toContain("login offline está habilitado");
    expect(source).not.toContain("cpfMascarado");
    expect(source).toContain("autenticarPorCpf");
    expect(source).toContain("O login exige conexão com o Córtex.");
    expect(source).toContain('"Entrar"');
    expect(source).toContain("Usar passkey");
    expect(source).toContain("authenticateWithPasskey");
    const forbiddenPublicLoginTerms = [
      "Enviar c\u00f3digo",
      "C\u00f3digo de acesso",
      "Reenviar c\u00f3digo",
      'autoComplete="one-time-' + 'code"',
      "chall" + "enge",
      "e-" + "mail",
      "em" + "ail",
      "login__" + "divider",
    ];
    for (const term of forbiddenPublicLoginTerms) {
      expect(source).not.toContain(term);
    }
    expect(source).not.toContain("PIN");
  });
});
