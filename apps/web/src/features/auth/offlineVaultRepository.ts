import { openDB, type DBSchema, type IDBPDatabase } from "idb";

import type {
  CollaborativeOfflineMetadata,
  OfflineCpfGrantMetadata,
  OfflinePasswordVaultMetadata,
  OfflineVaultMetadata,
} from "./offlineVault.types";
import { isOfflinePasswordVaultMetadata } from "./passwordOfflineVault";

const VAULT_DATABASE_NAME = "cortex-auth-vaults";
const VAULT_DATABASE_VERSION = 3;

interface OfflineVaultDbSchema extends DBSchema {
  vaults: {
    key: string;
    value: OfflineVaultMetadata;
    indexes: { "by-updated-at": string; "by-owner": string };
  };
  cpf_grants: {
    key: string;
    value: CollaborativeOfflineMetadata;
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

export async function listCollaborativeOfflineGrantMetadata(): Promise<
  OfflineCpfGrantMetadata[]
> {
  const database = await getVaultDatabase();
  return (await database.getAll("cpf_grants"))
    .filter((record): record is OfflineCpfGrantMetadata => record.versao === 2)
    .sort((left, right) =>
      right.atualizadoEm.localeCompare(left.atualizadoEm)
    );
}

export async function listCollaborativeOfflineMetadata(): Promise<
  CollaborativeOfflineMetadata[]
> {
  const database = await getVaultDatabase();
  return (await database.getAll("cpf_grants")).sort((left, right) =>
    right.atualizadoEm.localeCompare(left.atualizadoEm)
  );
}

export async function hasCollaborativeOfflineGrantMetadata(): Promise<boolean> {
  const database = await getVaultDatabase();
  return (await database.count("cpf_grants")) > 0;
}

export async function hasCollaborativePasswordVaultMetadata(): Promise<boolean> {
  const records = await listCollaborativeOfflineMetadata();
  return records.slice(0, 20).some(isOfflinePasswordVaultMetadata);
}

export async function deleteCollaborativeOfflineGrantMetadata(
  key: string,
): Promise<void> {
  const database = await getVaultDatabase();
  await database.delete("cpf_grants", key);
}

export async function replaceLegacyGrantAfterV3Save(
  legacyKey: string | null,
  metadata: OfflinePasswordVaultMetadata,
): Promise<void> {
  if (!isOfflinePasswordVaultMetadata(metadata)) {
    throw new Error("Metadados do cofre offline inválidos.");
  }
  const database = await getVaultDatabase();
  const transaction = database.transaction("cpf_grants", "readwrite");
  await transaction.store.put(metadata);
  const confirmed = await transaction.store.get(metadata.key);
  if (
    !isOfflinePasswordVaultMetadata(confirmed) ||
    JSON.stringify(confirmed) !== JSON.stringify(metadata)
  ) {
    transaction.abort();
    throw new Error("O cofre offline não pôde ser confirmado.");
  }
  if (legacyKey !== null && legacyKey !== metadata.key) {
    await transaction.store.delete(legacyKey);
  }
  await transaction.done;
}

/**
 * Os grants por CPF já guardados para esta pessoa.
 *
 * <p>O CPF em claro não é armazenado. A renovação reaproveita chave aleatória,
 * sal e verificador PBKDF2 já existentes; o que precisa ser trocado é o grant
 * assinado, não a identidade que abre o registro.
 */
export async function listCollaborativeOfflineGrantsForOwner(
  ownerId: string,
): Promise<OfflineCpfGrantMetadata[]> {
  const database = await getVaultDatabase();
  return (await database.getAllFromIndex(
    "cpf_grants",
    "by-owner",
    ownerId,
  )).filter(
    (record): record is OfflineCpfGrantMetadata => record.versao === 2,
  );
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
      upgrade(database, oldVersion, _newVersion, transaction) {
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
        if (oldVersion < 3) {
          // A versão 1 guardava SHA-256(CPF), barato de enumerar após uma
          // cópia do IndexedDB. Não há migração segura sem o CPF em claro:
          // descarte o verificador e reprovisione o grant no próximo login.
          transaction.objectStore("cpf_grants").clear();
        }
      },
      terminated() {
        databasePromise = null;
      },
    },
  );
}
