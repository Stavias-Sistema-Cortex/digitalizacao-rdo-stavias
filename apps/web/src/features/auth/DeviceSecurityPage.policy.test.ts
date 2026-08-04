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
    expect(source).not.toContain("autenticarPorCpf");
    expect(source).not.toContain("saveCollaborativeOfflineGrant");
    expect(source).not.toContain("CPF");
    expect(source).not.toContain("PIN");
  });

  /**
   * Fechar o pedido do autenticador não é defeito. O aviso vermelho com o
   * link da especificação do WebAuthn tratava como falha quem apenas
   * desistiu, e não havia ali nada a corrigir nem a ler.
   */
  it("volta ao repouso quando a passkey é cancelada, sem avisar erro", () => {
    const source = readFileSync(
      new URL("./DeviceSecurityPage.tsx", import.meta.url),
      "utf8",
    );

    expect(source).toContain('error.name === "NotAllowedError"');
    expect(source).toContain('error.name === "AbortError"');
    expect(source).toMatch(/desistencia\(error\)[\s\S]{0,400}kind: "idle"/);
  });

  it("adota o cabeçalho operacional compartilhado sem faixa superior separada", () => {
    const source = readFileSync(
      new URL("./DeviceSecurityPage.tsx", import.meta.url),
      "utf8",
    );

    expect(source).toContain(
      'import { CortexPageHeader } from "../../components/header/CortexPageHeader";',
    );
    expect(source).toContain("<CortexPageHeader");
    expect(source).toContain('eyebrow="Córtex · Conta"');
    expect(source).not.toContain('<header className="topbar">');
  });
});
