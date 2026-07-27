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
