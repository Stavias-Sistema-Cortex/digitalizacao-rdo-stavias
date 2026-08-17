import { clearSessionForCurrentDocument } from "./authSession";
import { onlyDigits } from "./loginValidation";
import {
  activateOfflineGrant,
  verifySignedOfflineGrant,
} from "./offlineVault";
import type {
  LegacyOfflineCpfGrantMetadata,
  OfflinePasswordVaultMetadata,
  SignedOfflineGrant,
} from "./offlineVault.types";
import {
  listCollaborativeOfflineGrantMetadata,
  listCollaborativeOfflineMetadata,
  replaceLegacyGrantAfterV3Save,
} from "./offlineVaultRepository";
import {
  createPasswordOfflineVault,
  isOfflinePasswordVaultMetadata,
  matchesPasswordVaultCpf,
  OfflinePasswordVaultUnlockError,
  openPasswordOfflineVault,
} from "./passwordOfflineVault";
import {
  bytesEqual,
  fromBase64Url,
} from "./webauthnCodec";

const CPF_KDF_ITERATIONS = 600_000;
const CPF_SALT_BYTES = 16;
const MAX_CPF_GRANTS_TO_CHECK = 20;

export class OfflineGrantOwnerMismatchError extends Error {
  constructor() {
    super("O grant offline não corresponde à identidade autenticada.");
    this.name = "OfflineGrantOwnerMismatchError";
  }
}

export async function saveCollaborativeOfflineGrant(
  cpf: string,
  password: string,
  signedGrant: SignedOfflineGrant,
  authenticatedOwnerId: string,
): Promise<OfflinePasswordVaultMetadata> {
  const canonicalCpf = requireCanonicalCpf(cpf);
  const verified = await verifySignedOfflineGrant(signedGrant);
  if (verified.claims.colaboradorId !== authenticatedOwnerId) {
    throw new OfflineGrantOwnerMismatchError();
  }
  if (verified.claims.versao !== 2) {
    throw new OfflinePasswordVaultUnlockError();
  }
  const legacy = await loadLegacyCollaborativeOfflineGrant(canonicalCpf);
  const metadata = await createPasswordOfflineVault({
    cpf: canonicalCpf,
    password,
    signedGrant,
    authenticatedOwnerId,
  });
  await replaceLegacyGrantAfterV3Save(legacy?.key ?? null, metadata);
  return metadata;
}

export async function loadCollaborativeOfflineGrant(
  cpf: string,
): Promise<OfflinePasswordVaultMetadata | null> {
  const canonicalCpf = requireCanonicalCpf(cpf);
  const candidates = await listCollaborativeOfflineMetadata();
  for (const candidate of candidates.slice(0, MAX_CPF_GRANTS_TO_CHECK)) {
    if (
      isOfflinePasswordVaultMetadata(candidate) &&
      await matchesPasswordVaultCpf(canonicalCpf, candidate)
    ) {
      return candidate;
    }
  }
  return null;
}

export async function unlockCollaborativeOfflineGrant(
  cpf: string,
  password: string,
  metadata: OfflinePasswordVaultMetadata,
): Promise<void> {
  clearSessionForCurrentDocument();
  try {
    const result = await openPasswordOfflineVault({
      cpf,
      password,
      metadata,
    });
    await replaceLegacyGrantAfterV3Save(null, result.metadata);
    if (result.kind !== "UNLOCKED") {
      throw new OfflinePasswordVaultUnlockError();
    }
    activateOfflineGrant(result.claims);
  } catch (cause: unknown) {
    throw new OfflinePasswordVaultUnlockError({ cause });
  }
}

async function loadLegacyCollaborativeOfflineGrant(
  canonicalCpf: string,
): Promise<LegacyOfflineCpfGrantMetadata | null> {
  const candidates = await listCollaborativeOfflineGrantMetadata();
  for (const candidate of candidates.slice(0, MAX_CPF_GRANTS_TO_CHECK)) {
    try {
      if (await matchesLegacyCpf(canonicalCpf, candidate)) {
        return candidate;
      }
    } catch {
      continue;
    }
  }
  return null;
}

async function matchesLegacyCpf(
  canonicalCpf: string,
  metadata: LegacyOfflineCpfGrantMetadata,
): Promise<boolean> {
  const salt = fromBase64Url(metadata.cpfSalt, CPF_SALT_BYTES);
  const expected = fromBase64Url(metadata.cpfVerifier, 32);
  const actual = await deriveLegacyCpfVerifier(canonicalCpf, salt);
  try {
    return bytesEqual(actual, expected);
  } finally {
    actual.fill(0);
  }
}

async function deriveLegacyCpfVerifier(
  canonicalCpf: string,
  salt: Uint8Array<ArrayBuffer>,
): Promise<Uint8Array<ArrayBuffer>> {
  const bytes = new TextEncoder().encode(canonicalCpf);
  try {
    const material = await crypto.subtle.importKey(
      "raw",
      bytes,
      "PBKDF2",
      false,
      ["deriveBits"],
    );
    return new Uint8Array(await crypto.subtle.deriveBits(
      {
        name: "PBKDF2",
        hash: "SHA-256",
        salt,
        iterations: CPF_KDF_ITERATIONS,
      },
      material,
      256,
    ));
  } finally {
    bytes.fill(0);
  }
}

function requireCanonicalCpf(cpf: string): string {
  const canonical = onlyDigits(cpf);
  if (!/^\d{11}$/.test(canonical)) {
    throw new OfflinePasswordVaultUnlockError();
  }
  return canonical;
}
