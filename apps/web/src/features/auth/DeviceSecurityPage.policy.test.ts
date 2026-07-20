import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

describe("DeviceSecurityPage policy", () => {
  it("registra passkey somente por gesto explícito e informa ausência de PRF", () => {
    const source = readFileSync(
      new URL("./DeviceSecurityPage.tsx", import.meta.url),
      "utf8",
    );

    expect(source).toContain("Registrar passkey neste dispositivo");
    expect(source).toContain("registerPasskey");
    expect(source).toContain("renewOfflineAccess");
    expect(source).toContain("Atualizar acesso offline");
    expect(source).toContain('offlineVault === "READY"');
    expect(source).toContain("não oferece PRF");
    expect(source).not.toContain("useEffect(() => registerPasskey");
    expect(source).not.toContain("CPF");
    expect(source).not.toContain("PIN");
  });
});
