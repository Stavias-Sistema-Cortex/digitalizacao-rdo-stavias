import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

describe("App normal authentication policy", () => {
  it("keeps the CPF plus passkey login as the unauthenticated fallback", () => {
    const source = readFileSync(
      new URL("./App.tsx", import.meta.url),
      "utf8",
    );

    expect(source).toContain(
      'import { LoginPage } from "./features/auth/LoginPage"',
    );
    expect(source).not.toContain("EmailOtpAccessForm");
    expect(source).not.toContain("PostgresqlAccessPage");
    expect(source).toContain("hasCollaborativeOfflineGrantMetadata");

    const offlineUnlockDecision = source.indexOf(
      "(offlineVault || hasCollaborativeCpfGrant)",
    );
    const loginFallback = source.indexOf("return <LoginPage />;");

    expect(offlineUnlockDecision).toBeGreaterThan(-1);
    expect(loginFallback).toBeGreaterThan(offlineUnlockDecision);
  });
});

/**
 * A volta do modo offline para o online não pode depender só do evento de
 * rede. Quem abriu o app com o servidor fora do ar destrava os dados pelo CPF
 * sem nunca ter perdido a conexão: o navegador nunca dispara "online" — ele
 * sempre esteve online — e a sessão ficaria isolada até alguém recarregar a
 * página. É exatamente essa pessoa que ficava presa.
 */
describe("App offline session handoff", () => {
  it("tenta retomar a sessão pelo evento de rede e pela sessão offline", () => {
    const source = readFileSync(
      new URL("./App.tsx", import.meta.url),
      "utf8",
    );

    expect(source).toContain('window.addEventListener("online", handleOnline)');
    expect(source).toMatch(
      /handleOnline\(\)[\s\S]{0,400}retomarSessaoOnline\(\)/,
    );
    // O segundo gatilho: existe sessão offline e há rede.
    expect(source).toMatch(
      /const sessaoOffline = session !== null && hasOfflineSession\(\)/,
    );
    expect(source).toMatch(
      /\[sessaoOffline, online\]/,
    );
  });

  /**
   * O grant precisa ser reemitido enquanto há rede; era o que faltava para o
   * aparelho não travar depois de 24 horas.
   */
  it("renova o grant offline a cada sincronização concluída", () => {
    const source = readFileSync(
      new URL("./App.tsx", import.meta.url),
      "utf8",
    );

    expect(source).toContain("renovarGrantOfflineSePreciso");
    expect(source).toContain(
      "window.addEventListener(SYNC_COMPLETED_EVENT, renovar)",
    );
  });
});
