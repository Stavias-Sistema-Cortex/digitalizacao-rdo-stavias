import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

describe("LoginPage auth policy", () => {
  it("uses the official Cortex lockup as one accessible image", () => {
    const loginPageSource = readFileSync(
      new URL("./LoginPage.tsx", import.meta.url),
      "utf8",
    );
    const loginCss = readFileSync(
      new URL("./LoginPage.css", import.meta.url),
      "utf8",
    );

    expect(loginPageSource).toContain(
      'import cortexLogo from "../../assets/login/cortex-logo.png"',
    );
    expect(loginPageSource).toContain('alt="Stavias Córtex"');
    expect(loginPageSource).toContain('className="login__brand-lockup"');
    expect(loginPageSource).toContain("draggable={false}");
    expect(loginPageSource).not.toContain("staviasTile");
    expect(loginCss).toMatch(
      /\.login__brand-lockup\s*\{[^}]*max-width:\s*440px/s,
    );
  });

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
