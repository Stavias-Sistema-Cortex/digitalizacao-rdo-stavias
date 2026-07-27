import "fake-indexeddb/auto";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { getSession } from "./authSession";
import {
  saveCollaborativeOfflineGrant,
  unlockCollaborativeOfflineGrant,
} from "./collaborativeOfflineGrant";
import { clearOfflineGrant } from "./offlineVault";
import type {
  OfflineGrantClaims,
  SignedOfflineGrant,
} from "./offlineVault.types";
import { toBase64Url } from "./webauthnCodec";

const now = Date.parse("2026-07-14T12:00:00Z");

describe("grant colaborativo de CPF", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(now);
    clearOfflineGrant();
  });

  afterEach(() => {
    clearOfflineGrant();
    vi.useRealTimers();
  });

  it("armazena o CPF canônico somente como hash e libera seu escopo", async () => {
    const fixture = await signedGrantFixture();

    const metadata = await saveCollaborativeOfflineGrant(
      "111.444.777-35",
      fixture.grant,
    );

    expect(metadata.cpfHash).not.toBe("11144477735");
    expect(JSON.stringify(metadata)).not.toContain("11144477735");

    await expect(
      unlockCollaborativeOfflineGrant("11144477735", metadata),
    ).resolves.toBeUndefined();
    expect(getSession()).toEqual({
      colaboradorId: fixture.claims.colaboradorId,
      nome: fixture.claims.nome,
      papelAcesso: fixture.claims.papelAcesso,
      escopoGlobal: fixture.claims.escopoGlobal,
      obraIds: fixture.claims.obraIds,
      expiraEm: fixture.claims.expiraEm,
    });
  });

  it("rejeita um CPF diferente sem criar uma sessão local", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await saveCollaborativeOfflineGrant(
      "11144477735",
      fixture.grant,
    );

    await expect(
      unlockCollaborativeOfflineGrant("11144477734", metadata),
    ).rejects.toThrow("CPF não corresponde");

    expect(getSession()).toBeNull();
  });

  it("rejeita grants adulterados ou expirados", async () => {
    const fixture = await signedGrantFixture({
      expiraEm: "2026-07-14T12:01:00Z",
    });
    const metadata = await saveCollaborativeOfflineGrant(
      "11144477735",
      fixture.grant,
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
