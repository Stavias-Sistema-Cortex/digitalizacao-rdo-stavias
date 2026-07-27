import { openDB, type DBSchema, type IDBPDatabase } from "idb";

import type {
  OfflineCpfGrantMetadata,
  OfflineVaultMetadata,
} from "./offlineVault.types";

const VAULT_DATABASE_NAME = "cortex-auth-vaults";
const VAULT_DATABASE_VERSION = 2;

interface OfflineVaultDbSchema extends DBSchema {
  vaults: {
    key: string;
    value: OfflineVaultMetadata;
    indexes: { "by-updated-at": string; "by-owner": string };
  };
  cpf_grants: {
    key: string;
    value: OfflineCpfGrantMetadata;
    indexes: { "by-updated-at": string; "by-owner": string };
  };
}

let databasePromise: Promise<IDBPDatabase<OfflineVaultDbSchema>> | null = null;

export async function saveOfflineVaultMetadata(
  metadata: OfflineVaultMetadata,
): Promise<void> {
  const database = await getVaultDatabase();
  await database.put("vaults", metadata);
}

export async function loadOfflineVaultMetadata(): Promise<
  OfflineVaultMetadata | null
> {
  const database = await getVaultDatabase();
  const cursor = await database
    .transaction("vaults")
    .store.index("by-updated-at")
    .openCursor(null, "prev");
  return cursor?.value ?? null;
}

export async function loadOfflineVaultMetadataForOwner(
  ownerId: string,
): Promise<OfflineVaultMetadata | null> {
  const database = await getVaultDatabase();
  const records = await database.getAllFromIndex(
    "vaults",
    "by-owner",
    ownerId,
  );
  return records.sort((left, right) =>
    right.atualizadoEm.localeCompare(left.atualizadoEm),
  )[0] ?? null;
}

export async function deleteOfflineVaultMetadata(
  key?: string,
): Promise<void> {
  const database = await getVaultDatabase();
  if (key) {
    await database.delete("vaults", key);
    return;
  }
  await database.clear("vaults");
}

export async function saveCollaborativeOfflineGrantMetadata(
  metadata: OfflineCpfGrantMetadata,
): Promise<void> {
  const database = await getVaultDatabase();
  await database.put("cpf_grants", metadata);
}

export async function loadCollaborativeOfflineGrantMetadata(
  cpfHash: string,
): Promise<OfflineCpfGrantMetadata | null> {
  const database = await getVaultDatabase();
  return await database.get("cpf_grants", cpfHash) ?? null;
}

function getVaultDatabase(): Promise<IDBPDatabase<OfflineVaultDbSchema>> {
  databasePromise ??= openVaultDatabase();
  return databasePromise;
}

function openVaultDatabase(): Promise<IDBPDatabase<OfflineVaultDbSchema>> {
  return openDB<OfflineVaultDbSchema>(
    VAULT_DATABASE_NAME,
    VAULT_DATABASE_VERSION,
    {
      upgrade(database, oldVersion) {
        if (oldVersion < 1) {
          const store = database.createObjectStore("vaults", {
            keyPath: "key",
          });
          store.createIndex("by-updated-at", "atualizadoEm");
          store.createIndex("by-owner", "ownerId");
        }
        if (oldVersion < 2) {
          const grants = database.createObjectStore("cpf_grants", {
            keyPath: "key",
          });
          grants.createIndex("by-updated-at", "atualizadoEm");
          grants.createIndex("by-owner", "ownerId");
        }
      },
      terminated() {
        databasePromise = null;
      },
    },
  );
}
