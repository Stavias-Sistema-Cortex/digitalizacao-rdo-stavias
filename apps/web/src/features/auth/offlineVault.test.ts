import { beforeEach, describe, expect, it, vi } from "vitest";

import { toBase64Url } from "./webauthnCodec";
import {
  clearOfflineGrant,
  createOfflineVault,
  getOfflineGrant,
  renewOfflineVault,
  unlockOfflineVault,
} from "./offlineVault";
import type {
  OfflineGrantClaims,
  SignedOfflineGrant,
} from "./offlineVault.types";

const now = Date.parse("2026-07-14T12:00:00Z");
const credentialId = crypto.getRandomValues(new Uint8Array(32));
const prfSalt = crypto.getRandomValues(new Uint8Array(32));
const prfOutput = crypto.getRandomValues(new Uint8Array(32));
const fetchMock = vi.fn();
const localValues = new Map<string, string>();

vi.stubGlobal("fetch", fetchMock);
vi.stubGlobal("localStorage", {
  getItem: (key: string) => localValues.get(key) ?? null,
  removeItem: (key: string) => localValues.delete(key),
  setItem: (key: string, value: string) => localValues.set(key, value),
});

describe("offlineVault PRF-only", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localValues.clear();
    clearOfflineGrant();
    localStorage.removeItem("cortex.auth.cpfFilter");
  });

  it("retorna PRF_UNAVAILABLE sem chamar CPF, Bloom ou rede", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await createOfflineVault({
      credentialId: toBase64Url(credentialId),
      rpId: "cortex.example.invalid",
      prfSalt: toBase64Url(prfSalt),
      prfOutput,
      signedGrant: fixture.grant,
      allowedKeyFingerprints: [fixture.fingerprint],
      now: () => now,
    });
    const get = vi.fn().mockResolvedValue(
      assertionCredential(credentialId, null),
    );
    vi.stubGlobal("navigator", { credentials: { get } });

    await expect(
      unlockOfflineVault(metadata, {
        allowedKeyFingerprints: [fixture.fingerprint],
        now: () => now,
      }),
    ).resolves.toBe("PRF_UNAVAILABLE");

    expect(fetchMock).not.toHaveBeenCalled();
    expect(localStorage.getItem("cortex.auth.cpfFilter")).toBeNull();
    expect(getOfflineGrant()).toBeNull();
  });

  it("deriva AES-GCM via HKDF, valida assinatura e ativa o grant", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await createOfflineVault({
      credentialId: toBase64Url(credentialId),
      rpId: "cortex.example.invalid",
      prfSalt: toBase64Url(prfSalt),
      prfOutput,
      signedGrant: fixture.grant,
      allowedKeyFingerprints: [fixture.fingerprint],
      now: () => now,
    });
    vi.stubGlobal("navigator", {
      credentials: {
        get: vi.fn().mockResolvedValue(
          assertionCredential(credentialId, prfOutput),
        ),
      },
    });

    await expect(
      unlockOfflineVault(metadata, {
        allowedKeyFingerprints: [fixture.fingerprint],
        now: () => now,
      }),
    ).resolves.toBe("UNLOCKED");

    expect(getOfflineGrant()).toEqual(fixture.claims);
    expect(JSON.stringify(metadata)).not.toContain(
      toBase64Url(prfOutput),
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("rejeita chave não fixada, assinatura alterada e grant expirado", async () => {
    const fixture = await signedGrantFixture();
    await expect(
      createOfflineVault({
        credentialId: toBase64Url(credentialId),
        rpId: "cortex.example.invalid",
        prfSalt: toBase64Url(prfSalt),
        prfOutput,
        signedGrant: fixture.grant,
        allowedKeyFingerprints: ["x".repeat(43)],
        now: () => now,
      }),
    ).rejects.toThrow("assinatura");

    const tampered: SignedOfflineGrant = {
      ...fixture.grant,
      signature: `${fixture.grant.signature[0] === "A" ? "B" : "A"}${fixture.grant.signature.slice(1)}`,
    };
    await expect(
      createOfflineVault({
        credentialId: toBase64Url(credentialId),
        rpId: "cortex.example.invalid",
        prfSalt: toBase64Url(prfSalt),
        prfOutput,
        signedGrant: tampered,
        allowedKeyFingerprints: [fixture.fingerprint],
        now: () => now,
      }),
    ).rejects.toThrow("assinatura");

    const expired = await signedGrantFixture({
      emitidoEm: "2026-07-14T09:00:00Z",
      expiraEm: "2026-07-14T10:00:00Z",
    });
    await expect(
      createOfflineVault({
        credentialId: toBase64Url(credentialId),
        rpId: "cortex.example.invalid",
        prfSalt: toBase64Url(prfSalt),
        prfOutput,
        signedGrant: expired.grant,
        allowedKeyFingerprints: [expired.fingerprint],
        now: () => now,
      }),
    ).rejects.toThrow("expirou");
  });

  it("rejeita credencial diferente da vinculada ao cofre", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await createOfflineVault({
      credentialId: toBase64Url(credentialId),
      rpId: "cortex.example.invalid",
      prfSalt: toBase64Url(prfSalt),
      prfOutput,
      signedGrant: fixture.grant,
      allowedKeyFingerprints: [fixture.fingerprint],
      now: () => now,
    });
    vi.stubGlobal("navigator", {
      credentials: {
        get: vi.fn().mockResolvedValue(
          assertionCredential(
            crypto.getRandomValues(new Uint8Array(32)),
            prfOutput,
          ),
        ),
      },
    });

    await expect(
      unlockOfflineVault(metadata, {
        allowedKeyFingerprints: [fixture.fingerprint],
        now: () => now,
      }),
    ).rejects.toThrow("credencial");
    expect(getOfflineGrant()).toBeNull();
  });

  it("renova usando a mesma PRF sem persistir o resultado do autenticador", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await createOfflineVault({
      credentialId: toBase64Url(credentialId),
      rpId: "cortex.example.invalid",
      prfSalt: toBase64Url(prfSalt),
      prfOutput,
      signedGrant: fixture.grant,
      allowedKeyFingerprints: [fixture.fingerprint],
      now: () => now,
    });
    vi.stubGlobal("navigator", {
      credentials: {
        get: vi.fn().mockResolvedValue(
          assertionCredential(credentialId, prfOutput),
        ),
      },
    });

    const renewed = await renewOfflineVault(
      metadata,
      async () => fixture.grant,
      {
        allowedKeyFingerprints: [fixture.fingerprint],
        now: () => now,
      },
    );

    expect(renewed).not.toBe("PRF_UNAVAILABLE");
    expect((renewed as typeof metadata).key).toBe(metadata.key);
    expect(JSON.stringify(renewed)).not.toContain(toBase64Url(prfOutput));
  });
});

async function signedGrantFixture(
  overrides: Partial<OfflineGrantClaims> = {},
): Promise<{
  claims: OfflineGrantClaims;
  grant: SignedOfflineGrant;
  fingerprint: string;
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
  const payloadBytes = new TextEncoder().encode(JSON.stringify(claims));
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    keyPair.privateKey,
    payloadBytes,
  );
  const publicKeySpki = await crypto.subtle.exportKey(
    "spki",
    keyPair.publicKey,
  );
  const fingerprint = toBase64Url(
    await crypto.subtle.digest("SHA-256", publicKeySpki),
  );
  return {
    claims,
    fingerprint,
    grant: {
      keyId: "offline-test-v1",
      payload: toBase64Url(payloadBytes),
      signature: toBase64Url(signature),
      publicKeySpki: toBase64Url(publicKeySpki),
    },
  };
}

function assertionCredential(
  rawId: Uint8Array,
  prf: Uint8Array | null,
): Credential {
  return {
    id: toBase64Url(rawId),
    type: "public-key",
    rawId: rawId.buffer,
    getClientExtensionResults: () =>
      prf
        ? { prf: { results: { first: prf.buffer } } }
        : { prf: { enabled: false } },
  } as unknown as Credential;
}
