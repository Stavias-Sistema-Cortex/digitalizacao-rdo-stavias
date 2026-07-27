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
    const lockupImages = [
      ...loginPageSource.matchAll(
        /<img(?=[^>]*className="login__brand-lockup")[^>]*\/>/g,
      ),
    ];
    expect(lockupImages).toHaveLength(1);
    expect(loginCss).toMatch(
      /\.login__brand-lockup\s*\{[^}]*max-width:\s*440px/s,
    );
    expect(loginCss).toMatch(
      /@media \(max-width: 760px\)[\s\S]*?\.login__brand-lockup\s*\{[^}]*max-width:\s*100%/s,
    );
  });

  it("keeps photo, blur, and ink reveal out of the formal login", () => {
    const loginPageSource = readFileSync(
      new URL("./LoginPage.tsx", import.meta.url),
      "utf8",
    );
    const loginCss = readFileSync(
      new URL("./LoginPage.css", import.meta.url),
      "utf8",
    );

    expect(loginPageSource).not.toContain("construction-hero");
    expect(loginPageSource).not.toContain("stavias-canteiro");
    expect(loginCss).not.toContain("backdrop-filter");
    expect(loginCss).not.toMatch(/\bblur(?:\(|:)/);
    expect(`${loginPageSource}\n${loginCss}`).not.toContain("ink-reveal");
  });

  it("uses an ink focus ring for the login passkey action", () => {
    const loginCss = readFileSync(
      new URL("./LoginPage.css", import.meta.url),
      "utf8",
    );

    expect(loginCss).toMatch(
      /\.cortex-login button:focus-visible\s*\{[^}]*outline:\s*3px solid var\(--login-ink\)/s,
    );
  });

  it("defines the login action stack and secondary action only once", () => {
    const loginCss = readFileSync(
      new URL("./LoginPage.css", import.meta.url),
      "utf8",
    );

    expect(loginCss.match(/\.login__actions\s*\{/g)).toHaveLength(1);
    expect(
      loginCss.match(/\.cortex-login \.login__submit-secondary\s*\{/g),
    ).toHaveLength(1);
    expect(loginCss).toMatch(
      /\.login__actions \.login__submit\s*\{[^}]*margin-top:\s*0/s,
    );
  });

  it("uses a teal focus ring for offline unlock actions", () => {
    const offlineCss = readFileSync(
      new URL("./OfflineUnlockPage.css", import.meta.url),
      "utf8",
    );

    expect(offlineCss).toMatch(
      /\.offline-unlock__primary:focus-visible,\s*\.offline-unlock__secondary:focus-visible\s*\{[^}]*outline:\s*3px solid #124e4a/s,
    );
  });

  it("keeps the offline card frame structural instead of permanently yellow", () => {
    const offlineCss = readFileSync(
      new URL("./OfflineUnlockPage.css", import.meta.url),
      "utf8",
    );

    expect(offlineCss).toMatch(
      /\.offline-unlock__card\s*\{[^}]*border:\s*2px solid #111312/s,
    );
    expect(offlineCss).not.toMatch(
      /\.offline-unlock__card\s*\{[^}]*border:\s*2px solid #f2c800/s,
    );
  });

  it("usa CPF direto e mantém uma única alternativa por passkey", () => {
    const source = readFileSync(
      new URL("./LoginPage.tsx", import.meta.url),
      "utf8",
    );

    expect(source).not.toContain("getCachedFilter");
    expect(source).not.toContain("filtroOfflinePronto");
    expect(source).not.toContain("login offline está habilitado");
    expect(source).not.toContain("cpfMascarado");
    expect(source).toContain("autenticarPorCpf(onlyDigits(cpf))");
    expect(source).not.toContain("emailOtpApi");
    expect(source).not.toContain("requestCpfOtpChallenge");
    expect(source).not.toContain("verifyEmailOtpChallenge");
    expect(source).not.toMatch(/e-mail/i);
    expect(source).toContain("O login exige conexão com o Córtex.");
    expect(source).toContain("authenticateWithPasskey(cpf)");
    expect(source.match(/authenticateWithPasskey\(cpf\)/g)).toHaveLength(1);
    expect(source).toContain('"Entrar"');
    expect(source).toContain('"Entrar com passkey"');
    expect(source).not.toContain("Código de acesso");
    expect(source).not.toContain('autoComplete="one-time-code"');
    expect(source).not.toContain("PIN");
    expect(source).not.toContain("offlineVault");
    expect(source).not.toMatch(/localStorage|sessionStorage|indexedDB/);
  });
});
