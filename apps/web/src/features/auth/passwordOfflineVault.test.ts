import "fake-indexeddb/auto";

import { openDB } from "idb";
import { beforeEach, describe, expect, it } from "vitest";

import { scopeFingerprint } from "../../lib/db/localDataNamespace";
import type {
  CurrentOfflineGrantClaims,
  OfflinePasswordVaultMetadata,
  SignedOfflineGrant,
} from "./offlineVault.types";
import {
  clearPasswordVaultKeys,
  createPasswordOfflineVault,
  OfflinePasswordVaultUnlockError,
  openPasswordOfflineVault,
  resealPasswordOfflineVault,
} from "./passwordOfflineVault";
import {
  deleteCollaborativeOfflineGrantMetadata,
  replaceLegacyGrantAfterV3Save,
} from "./offlineVaultRepository";
import { fromBase64Url, toBase64Url } from "./webauthnCodec";

const NOW = Date.parse("2026-07-14T12:00:00Z");
const CPF = "11144477735";
const PASSWORD = "Senha individual forte 123!";
const OWNER_ID = "00000000-0000-4000-8000-000000000001";
const WORKSITE_ID = "00000000-0000-4000-8000-000000000002";

describe("passwordOfflineVault", () => {
  beforeEach(() => {
    clearPasswordVaultKeys();
  });

  it("persists only the exact public v3 envelope and opens with CPF plus password", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await createVault(fixture.grant);

    expect(Object.keys(metadata).sort()).toEqual([
      "atualizadoEm",
      "ciphertext",
      "cpfSalt",
      "cpfVerifier",
      "failedAttemptState",
      "iv",
      "kdf",
      "kdfIterations",
      "key",
      "passwordSalt",
      "serverKeyFingerprint",
      "versao",
    ]);
    expect(metadata).toMatchObject({
      versao: 3,
      kdf: "PBKDF2-SHA256",
      kdfIterations: 600_000,
      failedAttemptState: {
        windowStartedAt: null,
        failures: 0,
        blockedUntil: null,
      },
    });
    expect(metadata).not.toHaveProperty("authEpoch");
    expect(metadata).not.toHaveProperty("lastTrustedTime");
    expect(metadata).not.toHaveProperty("ownerId");
    expect(metadata).not.toHaveProperty("scopeFingerprint");
    expect(metadata).not.toHaveProperty("signedGrant");
    const persisted = JSON.stringify(metadata);
    const expectedScope = await scopeFingerprint(
      OWNER_ID,
      `BETA:${WORKSITE_ID}`,
    );
    for (const secret of [
      CPF,
      PASSWORD,
      fixture.grant.payload,
      fixture.claims.nome,
      fixture.claims.papelAcesso,
      OWNER_ID,
      WORKSITE_ID,
      expectedScope,
    ]) {
      expect(persisted).not.toContain(secret);
    }

    await expect(openVault(metadata)).resolves.toMatchObject({
      kind: "UNLOCKED",
      claims: fixture.claims,
      metadata: {
        failedAttemptState: {
          windowStartedAt: null,
          failures: 0,
          blockedUntil: null,
        },
      },
    });
  });

  it("writes the generated v3 envelope to IndexedDB without clear identity or grant", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await createVault(fixture.grant);

    await replaceLegacyGrantAfterV3Save(null, metadata);
    const database = await openDB("cortex-auth-vaults");
    const persistedRecord = await database.get("cpf_grants", metadata.key);
    database.close();

    expect(persistedRecord).toEqual(metadata);
    expect(Object.keys(persistedRecord as object).sort()).toEqual([
      "atualizadoEm",
      "ciphertext",
      "cpfSalt",
      "cpfVerifier",
      "failedAttemptState",
      "iv",
      "kdf",
      "kdfIterations",
      "key",
      "passwordSalt",
      "serverKeyFingerprint",
      "versao",
    ]);
    const serializedRecord = JSON.stringify(persistedRecord);
    for (const clearValue of [
      CPF,
      PASSWORD,
      fixture.grant.payload,
      fixture.claims.nome,
      fixture.claims.papelAcesso,
      OWNER_ID,
      WORKSITE_ID,
      fixture.claims.expiraEm,
    ]) {
      expect(serializedRecord).not.toContain(clearValue);
    }
    expect(serializedRecord).not.toContain('"authEpoch"');
    expect(serializedRecord).not.toContain('"lastTrustedTime"');

    await deleteCollaborativeOfflineGrantMetadata(metadata.key);
  });

  it("returns the same bounded rejection for wrong CPF and wrong password", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await createVault(fixture.grant);

    const wrongCpf = await openPasswordOfflineVault({
      cpf: "11144477734",
      password: PASSWORD,
      metadata,
      now: () => NOW,
    });
    const wrongPassword = await openPasswordOfflineVault({
      cpf: CPF,
      password: "Outra senha forte 456!",
      metadata,
      now: () => NOW,
    });

    expect(wrongCpf.kind).toBe("REJECTED");
    expect(wrongPassword.kind).toBe("REJECTED");
    expect(wrongCpf.metadata.failedAttemptState.failures).toBe(1);
    expect(wrongPassword.metadata.failedAttemptState.failures).toBe(1);
    expect(new OfflinePasswordVaultUnlockError().message).toBe(
      "Não foi possível liberar o acesso offline neste aparelho.",
    );
  });

  it("rejects authenticated tampering of IV, ciphertext, fingerprint, and AAD", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await createVault(fixture.grant);
    const alteredIv = flipBase64UrlByte(metadata.iv);
    const alteredCiphertext = flipBase64UrlByte(metadata.ciphertext);
    const cases: OfflinePasswordVaultMetadata[] = [
      { ...metadata, iv: alteredIv },
      { ...metadata, ciphertext: alteredCiphertext },
      { ...metadata, serverKeyFingerprint: "x".repeat(43) },
      {
        ...metadata,
        key: "00000000-0000-4000-8000-000000000099",
      },
    ];

    for (const tampered of cases) {
      await expect(openVault(tampered)).resolves.toMatchObject({
        kind: "REJECTED",
      });
    }
  });

  it("limits five failures per fifteen-minute window and resets on success", async () => {
    const fixture = await signedGrantFixture();
    let metadata = await createVault(fixture.grant);
    let attemptTime = NOW;

    for (let failure = 1; failure <= 5; failure += 1) {
      const result = await openPasswordOfflineVault({
        cpf: CPF,
        password: "Outra senha forte 456!",
        metadata,
        now: () => attemptTime,
      });
      expect(result.kind).toBe("REJECTED");
      metadata = result.metadata;
      expect(metadata.failedAttemptState.failures).toBe(failure);
      if (failure < 5) {
        attemptTime = Date.parse(
          metadata.failedAttemptState.blockedUntil as string,
        );
      }
    }
    expect(metadata.failedAttemptState.blockedUntil).toBe(
      new Date(NOW + 15 * 60 * 1_000).toISOString(),
    );

    await expect(openPasswordOfflineVault({
      cpf: CPF,
      password: PASSWORD,
      metadata,
      now: () => NOW + 14 * 60 * 1_000,
    })).resolves.toMatchObject({ kind: "REJECTED" });

    await expect(openPasswordOfflineVault({
      cpf: CPF,
      password: PASSWORD,
      metadata,
      now: () => NOW + 15 * 60 * 1_000,
    })).resolves.toMatchObject({
      kind: "UNLOCKED",
      metadata: {
        failedAttemptState: {
          windowStartedAt: null,
          failures: 0,
          blockedUntil: null,
        },
      },
    });
  });

  it("accepts immediately before seven days and rejects at expiration", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await createVault(fixture.grant);
    const beforeExpiry = await openPasswordOfflineVault({
      cpf: CPF,
      password: PASSWORD,
      metadata,
      now: () => Date.parse(fixture.claims.expiraEm) - 1,
    });
    expect(beforeExpiry.kind).toBe("UNLOCKED");

    await expect(openPasswordOfflineVault({
      cpf: CPF,
      password: PASSWORD,
      metadata: beforeExpiry.metadata,
      now: () => Date.parse(fixture.claims.expiraEm),
    })).resolves.toMatchObject({ kind: "REJECTED" });
  });

  it("allows five minutes of clock rollback and rejects anything greater", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await createVault(fixture.grant);
    const advanced = await openPasswordOfflineVault({
      cpf: CPF,
      password: PASSWORD,
      metadata,
      now: () => NOW + 10 * 60 * 1_000,
    });
    expect(advanced.kind).toBe("UNLOCKED");

    const exactTolerance = await openPasswordOfflineVault({
      cpf: CPF,
      password: PASSWORD,
      metadata: advanced.metadata,
      now: () => NOW + 5 * 60 * 1_000,
    });
    expect(exactTolerance.kind).toBe("UNLOCKED");

    await expect(openPasswordOfflineVault({
      cpf: CPF,
      password: PASSWORD,
      metadata: exactTolerance.metadata,
      now: () => NOW + 5 * 60 * 1_000 - 1,
    })).resolves.toMatchObject({ kind: "REJECTED" });
  });

  it("reseals only while the non-extractable key remains in memory", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await createVault(fixture.grant);
    const renewed = await signedGrantFixture({ authEpoch: 8 });
    clearPasswordVaultKeys();

    await expect(resealPasswordOfflineVault(
      metadata,
      renewed.grant,
      OWNER_ID,
      () => NOW + 60_000,
    )).resolves.toBe("PASSWORD_REQUIRED");

    const opened = await openVault(metadata);
    expect(opened.kind).toBe("UNLOCKED");
    const resealed = await resealPasswordOfflineVault(
      opened.metadata,
      renewed.grant,
      OWNER_ID,
      () => NOW + 60_000,
    );
    expect(resealed).not.toBe("PASSWORD_REQUIRED");
    await expect(openVault(resealed as OfflinePasswordVaultMetadata))
      .resolves.toMatchObject({
        kind: "UNLOCKED",
        claims: { authEpoch: 8 },
      });
  });
});

async function createVault(grant: SignedOfflineGrant) {
  return createPasswordOfflineVault({
    cpf: CPF,
    password: PASSWORD,
    signedGrant: grant,
    authenticatedOwnerId: OWNER_ID,
    now: () => NOW,
  });
}

function openVault(metadata: OfflinePasswordVaultMetadata) {
  return openPasswordOfflineVault({
    cpf: CPF,
    password: PASSWORD,
    metadata,
    now: () => NOW,
  });
}

function flipBase64UrlByte(value: string): string {
  const bytes = fromBase64Url(value, 65_536);
  bytes[0] ^= 1;
  return toBase64Url(bytes);
}

async function signedGrantFixture(
  overrides: Partial<CurrentOfflineGrantClaims> = {},
): Promise<{
  claims: CurrentOfflineGrantClaims;
  grant: SignedOfflineGrant;
}> {
  const claims: CurrentOfflineGrantClaims = {
    versao: 2,
    colaboradorId: OWNER_ID,
    nome: "Colaborador Sintético",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [WORKSITE_ID],
    emitidoEm: "2026-07-14T12:00:00Z",
    expiraEm: "2026-07-21T12:00:00Z",
    authEpoch: 7,
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
  return {
    claims,
    grant: {
      keyId: "offline-test-v2",
      payload: toBase64Url(payloadBytes),
      signature: toBase64Url(signature),
      publicKeySpki: toBase64Url(publicKeySpki),
    },
  };
}
