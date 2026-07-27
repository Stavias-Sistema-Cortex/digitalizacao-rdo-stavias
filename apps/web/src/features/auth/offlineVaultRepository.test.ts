import "fake-indexeddb/auto";

import { openDB, type DBSchema } from "idb";
import { describe, expect, it } from "vitest";

import type {
  OfflineCpfGrantMetadata,
  OfflineVaultMetadata,
} from "./offlineVault.types";
import {
  hasCollaborativeOfflineGrantMetadata,
  loadOfflineVaultMetadata,
  saveCollaborativeOfflineGrantMetadata,
} from "./offlineVaultRepository";

const databaseName = "cortex-auth-vaults";

interface LegacyVaultSchema extends DBSchema {
  vaults: {
    key: string;
    value: OfflineVaultMetadata;
    indexes: { "by-updated-at": string; "by-owner": string };
  };
}

describe("repositório de cofres offline", () => {
  it("atualiza o banco v1 sem perder vaults e cria cpf_grants", async () => {
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
    const legacy = await openDB<LegacyVaultSchema>(databaseName, 1, {
      upgrade(database) {
        const vaults = database.createObjectStore("vaults", { keyPath: "key" });
        vaults.createIndex("by-updated-at", "atualizadoEm");
        vaults.createIndex("by-owner", "ownerId");
      },
    });
    await legacy.put("vaults", legacyVault);
    legacy.close();

    const grant: OfflineCpfGrantMetadata = {
      key: "cpf-grant",
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
      atualizadoEm: "2026-07-14T12:01:00Z",
    };

    expect(await hasCollaborativeOfflineGrantMetadata()).toBe(false);
    await saveCollaborativeOfflineGrantMetadata(grant);

    expect(await hasCollaborativeOfflineGrantMetadata()).toBe(true);
    expect(await loadOfflineVaultMetadata()).toEqual(legacyVault);
    const upgraded = await openDB(databaseName);
    expect([...upgraded.objectStoreNames]).toEqual(expect.arrayContaining([
      "vaults",
      "cpf_grants",
    ]));
    expect(await upgraded.get("cpf_grants", grant.key)).toEqual(grant);
    upgraded.close();
  });
});
