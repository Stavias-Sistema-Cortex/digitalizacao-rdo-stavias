import "fake-indexeddb/auto";

import { openDB, type DBSchema } from "idb";
import { describe, expect, it } from "vitest";

import type {
  OfflinePasswordVaultMetadata,
  OfflineCpfGrantMetadata,
  OfflineVaultMetadata,
} from "./offlineVault.types";
import {
  deleteCollaborativeOfflineGrantMetadata,
  hasCollaborativeOfflineGrantMetadata,
  hasCollaborativePasswordVaultMetadata,
  loadOfflineVaultMetadata,
  replaceLegacyGrantAfterV3Save,
  saveCollaborativeOfflineGrantMetadata,
} from "./offlineVaultRepository";

const databaseName = "cortex-auth-vaults";

type LegacyCpfGrantMetadata = {
  key: string;
  versao: 1;
  cpfHash: string;
  ownerId: string;
  scopeFingerprint: string;
  signedGrant: OfflineCpfGrantMetadata["signedGrant"];
  serverKeyFingerprint: string;
  atualizadoEm: string;
};

interface LegacyVaultSchema extends DBSchema {
  vaults: {
    key: string;
    value: OfflineVaultMetadata;
    indexes: { "by-updated-at": string; "by-owner": string };
  };
  cpf_grants: {
    key: string;
    value: LegacyCpfGrantMetadata;
    indexes: { "by-updated-at": string; "by-owner": string };
  };
}

describe("repositório de cofres offline", () => {
  it("atualiza o banco v2 sem perder vaults e remove verificadores rápidos de CPF", async () => {
    const legacyVault: OfflineVaultMetadata = {
      key: "legacy-vault",
      versao: 1,
      ownerId: "00000000-0000-4000-8000-000000000001",
      scopeFingerprint: "a".repeat(64),
      credentialId: "credential",
      rpId: "cortex.example.invalid",
      prfSalt: "a".repeat(43),
      iv: "a".repeat(16),
      ciphertext: "ciphertext",
      serverKeyFingerprint: "a".repeat(43),
      atualizadoEm: "2026-07-14T12:00:00Z",
    };
    const legacyGrant: LegacyCpfGrantMetadata = {
      key: "b".repeat(64),
      versao: 1,
      cpfHash: "b".repeat(64),
      ownerId: legacyVault.ownerId,
      scopeFingerprint: legacyVault.scopeFingerprint,
      signedGrant: {
        keyId: "offline-test-v1",
        payload: "payload",
        signature: "signature",
        publicKeySpki: "public-key",
      },
      serverKeyFingerprint: legacyVault.serverKeyFingerprint,
      atualizadoEm: "2026-07-14T12:00:00Z",
    };
    const legacy = await openDB<LegacyVaultSchema>(databaseName, 2, {
      upgrade(database) {
        const vaults = database.createObjectStore("vaults", { keyPath: "key" });
        vaults.createIndex("by-updated-at", "atualizadoEm");
        vaults.createIndex("by-owner", "ownerId");
        const grants = database.createObjectStore("cpf_grants", {
          keyPath: "key",
        });
        grants.createIndex("by-updated-at", "atualizadoEm");
        grants.createIndex("by-owner", "ownerId");
      },
    });
    await legacy.put("vaults", legacyVault);
    await legacy.put("cpf_grants", legacyGrant);
    legacy.close();

    const grant: OfflineCpfGrantMetadata = {
      key: "20000000-0000-4000-8000-000000000001",
      versao: 2,
      cpfSalt: "s".repeat(22),
      cpfVerifier: "v".repeat(43),
      ownerId: legacyVault.ownerId,
      scopeFingerprint: legacyVault.scopeFingerprint,
      signedGrant: {
        keyId: "offline-test-v1",
        payload: "payload",
        signature: "signature",
        publicKeySpki: "public-key",
      },
      serverKeyFingerprint: legacyVault.serverKeyFingerprint,
      atualizadoEm: "2026-07-14T12:01:00Z",
    };

    expect(await hasCollaborativeOfflineGrantMetadata()).toBe(false);
    await saveCollaborativeOfflineGrantMetadata(grant);

    expect(await hasCollaborativeOfflineGrantMetadata()).toBe(true);
    expect(await hasCollaborativePasswordVaultMetadata()).toBe(false);
    expect(await loadOfflineVaultMetadata()).toEqual(legacyVault);
    const passwordVault: OfflinePasswordVaultMetadata = {
      key: "20000000-0000-4000-8000-000000000002",
      versao: 3,
      cpfSalt: "s".repeat(22),
      cpfVerifier: "v".repeat(43),
      passwordSalt: "p".repeat(22),
      kdf: "PBKDF2-SHA256",
      kdfIterations: 600_000,
      iv: "i".repeat(16),
      ciphertext: "c".repeat(64),
      serverKeyFingerprint: "f".repeat(43),
      atualizadoEm: "2026-07-14T12:02:00Z",
      failedAttemptState: {
        windowStartedAt: null,
        failures: 0,
        blockedUntil: null,
      },
    };
    await expect(replaceLegacyGrantAfterV3Save(
      grant.key,
      { ...passwordVault, kdfIterations: 1 } as OfflinePasswordVaultMetadata,
    )).rejects.toThrow("inválidos");
    const beforeConfirmedWrite = await openDB(databaseName);
    expect(await beforeConfirmedWrite.get("cpf_grants", grant.key))
      .toEqual(grant);
    beforeConfirmedWrite.close();

    await replaceLegacyGrantAfterV3Save(grant.key, passwordVault);

    expect(await hasCollaborativePasswordVaultMetadata()).toBe(true);
    const upgraded = await openDB(databaseName);
    expect([...upgraded.objectStoreNames]).toEqual(expect.arrayContaining([
      "vaults",
      "cpf_grants",
    ]));
    expect(await upgraded.get("cpf_grants", grant.key)).toBeUndefined();
    expect(await upgraded.get("cpf_grants", passwordVault.key))
      .toEqual(passwordVault);
    upgraded.close();

    await deleteCollaborativeOfflineGrantMetadata(passwordVault.key);
    expect(await hasCollaborativePasswordVaultMetadata()).toBe(false);
    expect(await loadOfflineVaultMetadata()).toEqual(legacyVault);
  });
});
