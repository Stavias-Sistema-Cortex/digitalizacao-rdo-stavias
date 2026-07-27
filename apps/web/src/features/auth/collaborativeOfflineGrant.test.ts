import "fake-indexeddb/auto";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  getSession,
  hasOfflineSession,
  hasOnlineSession,
  setOfflineSession,
  setSession,
} from "./authSession";
import {
  loadCollaborativeOfflineGrant,
  saveCollaborativeOfflineGrant,
  unlockCollaborativeOfflineGrant,
} from "./collaborativeOfflineGrant";
import type { OfflineGrantClaims } from "./offlineVault.types";
import { toBase64Url } from "./webauthnCodec";

const now = Date.parse("2026-07-14T12:00:00Z");
const REMOTE_SESSION_ISOLATION_KEY = "cortex.auth.remote-session-isolation";
const localValues = new Map<string, string>();

vi.stubGlobal("localStorage", {
  getItem: (key: string) => localValues.get(key) ?? null,
  removeItem: (key: string) => localValues.delete(key),
  setItem: (key: string, value: string) => localValues.set(key, value),
});

describe("grant colaborativo de CPF", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(now);
    localValues.clear();
    clearSession();
  });

  afterEach(() => {
    clearSession();
    vi.useRealTimers();
  });

  it("armazena o CPF canônico somente como hash e libera seu escopo", async () => {
    const fixture = await signedGrantFixture();

    const metadata = await saveCollaborativeOfflineGrant(
      "111.444.777-35",
      fixture.grant,
      fixture.claims.colaboradorId,
    );

    expect(metadata.cpfHash).not.toBe("11144477735");
    expect(JSON.stringify(metadata)).not.toContain("11144477735");

    await expect(
      unlockCollaborativeOfflineGrant("11144477735", metadata),
    ).resolves.toBeUndefined();
    expect(localStorage.getItem(REMOTE_SESSION_ISOLATION_KEY)).toBe("1");
    expect(getSession()).toEqual({
      colaboradorId: fixture.claims.colaboradorId,
      nome: fixture.claims.nome,
      papelAcesso: fixture.claims.papelAcesso,
      escopoGlobal: fixture.claims.escopoGlobal,
      obraIds: fixture.claims.obraIds,
      expiraEm: fixture.claims.expiraEm,
    });
  });

  it("does not cache a signed grant for CPF A when the returned owner is collaborator B", async () => {
    const fixture = await signedGrantFixture();
    const expectedOwnerId = "00000000-0000-4000-8000-000000000003";
    const cpf = "00000000000";
    await expect(
      saveCollaborativeOfflineGrant(cpf, fixture.grant, expectedOwnerId),
    ).rejects.toThrow("identidade autenticada");

    await expect(loadCollaborativeOfflineGrant(cpf)).resolves.toBeNull();
    expect(getSession()).toBeNull();
  });

  it("rejeita um CPF diferente sem criar uma sessão local", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await saveCollaborativeOfflineGrant(
      "11144477735",
      fixture.grant,
      fixture.claims.colaboradorId,
    );

    await expect(
      unlockCollaborativeOfflineGrant("11144477734", metadata),
    ).rejects.toThrow("CPF não corresponde");

    expect(getSession()).toBeNull();
  });

  it("limpa uma sessão online existente quando o CPF não corresponde", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await saveCollaborativeOfflineGrant(
      "11144477735",
      fixture.grant,
      fixture.claims.colaboradorId,
    );
    setSession(existingProfile());
    expect(hasOnlineSession()).toBe(true);

    await expect(
      unlockCollaborativeOfflineGrant("11144477734", metadata),
    ).rejects.toThrow("CPF não corresponde");

    expect(getSession()).toBeNull();
  });

  it("limpa uma sessão online existente quando a assinatura é alterada", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await saveCollaborativeOfflineGrant(
      "11144477735",
      fixture.grant,
      fixture.claims.colaboradorId,
    );
    const tampered = {
      ...metadata,
      signedGrant: {
        ...metadata.signedGrant,
        signature: `${metadata.signedGrant.signature[0] === "A" ? "B" : "A"}${metadata.signedGrant.signature.slice(1)}`,
      },
    };
    setSession(existingProfile());
    expect(hasOnlineSession()).toBe(true);

    await expect(
      unlockCollaborativeOfflineGrant("11144477735", tampered),
    ).rejects.toThrow("assinatura");

    expect(getSession()).toBeNull();
  });

  it("limpa uma sessão offline existente quando a fingerprint é alterada", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await saveCollaborativeOfflineGrant(
      "11144477735",
      fixture.grant,
      fixture.claims.colaboradorId,
    );
    const tampered = {
      ...metadata,
      serverKeyFingerprint: "x".repeat(43),
    };
    setOfflineSession(existingProfile());
    expect(hasOfflineSession()).toBe(true);

    await expect(
      unlockCollaborativeOfflineGrant("11144477735", tampered),
    ).rejects.toThrow("assinatura");

    expect(getSession()).toBeNull();
  });

  it("limpa uma sessão offline existente quando o escopo é alterado", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await saveCollaborativeOfflineGrant(
      "11144477735",
      fixture.grant,
      fixture.claims.colaboradorId,
    );
    const tampered = {
      ...metadata,
      scopeFingerprint: "a".repeat(64),
    };
    setOfflineSession(existingProfile());
    expect(hasOfflineSession()).toBe(true);

    await expect(
      unlockCollaborativeOfflineGrant("11144477735", tampered),
    ).rejects.toThrow("escopo");

    expect(getSession()).toBeNull();
  });

  it("limpa uma sessão online existente quando o fingerprint do escopo é malformado", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await saveCollaborativeOfflineGrant(
      "11144477735",
      fixture.grant,
      fixture.claims.colaboradorId,
    );
    const malformed = {
      ...metadata,
      scopeFingerprint: "fingerprint-malformado",
    };
    setSession(existingProfile());
    expect(hasOnlineSession()).toBe(true);

    await expect(
      unlockCollaborativeOfflineGrant("11144477735", malformed),
    ).rejects.toThrow("Metadados do grant offline inválidos.");

    expect(getSession()).toBeNull();
  });

  it("rejeita grants adulterados ou expirados", async () => {
    const fixture = await signedGrantFixture({
      expiraEm: "2026-07-14T12:01:00Z",
    });
    const metadata = await saveCollaborativeOfflineGrant(
      "11144477735",
      fixture.grant,
      fixture.claims.colaboradorId,
    );
    const tampered = {
      ...metadata,
      signedGrant: {
        ...metadata.signedGrant,
        signature: `${metadata.signedGrant.signature[0] === "A" ? "B" : "A"}${metadata.signedGrant.signature.slice(1)}`,
      },
    };

    await expect(
      unlockCollaborativeOfflineGrant("11144477735", tampered),
    ).rejects.toThrow("assinatura");
    expect(getSession()).toBeNull();

    vi.setSystemTime(Date.parse("2026-07-14T12:02:00Z"));
    await expect(
      unlockCollaborativeOfflineGrant("11144477735", metadata),
    ).rejects.toThrow("expirou");
    expect(getSession()).toBeNull();
  });
});

function existingProfile() {
  return {
    colaboradorId: "00000000-0000-4000-8000-000000000003",
    nome: "Sessão existente",
    papelAcesso: "BETA" as const,
    escopoGlobal: false,
    obraIds: ["00000000-0000-4000-8000-000000000004"],
    expiraEm: "2026-07-14T20:00:00Z",
  };
}

async function signedGrantFixture(
  overrides: Partial<OfflineGrantClaims> = {},
): Promise<{
  claims: OfflineGrantClaims;
  grant: SignedOfflineGrant;
}> {
  const claims: OfflineGrantClaims = {
    versao: 1,
    colaboradorId: "00000000-0000-4000-8000-000000000001",
    nome: "Colaborador Sintético",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: ["00000000-0000-4000-8000-000000000002"],
    emitidoEm: "2026-07-14T11:55:00Z",
    expiraEm: "2026-07-14T20:00:00Z",
    ...overrides,
  };
  const keyPair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const payload = new TextEncoder().encode(JSON.stringify(claims));
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    keyPair.privateKey,
    payload,
  );
  const publicKeySpki = await crypto.subtle.exportKey("spki", keyPair.publicKey);
  return {
    claims,
    grant: {
      keyId: "offline-test-v1",
      payload: toBase64Url(payload),
      signature: toBase64Url(signature),
      publicKeySpki: toBase64Url(publicKeySpki),
    },
  };
}
