import { scopeFingerprint } from "../../lib/db/localDataNamespace";
import { clearSessionForCurrentDocument } from "./authSession";
import { onlyDigits } from "./loginValidation";
import {
  activateOfflineGrant,
  offlineGrantScopeMaterial,
  verifySignedOfflineGrant,
} from "./offlineVault";
import type {
  OfflineCpfGrantMetadata,
  SignedOfflineGrant,
} from "./offlineVault.types";
import {
  listCollaborativeOfflineGrantMetadata,
  saveCollaborativeOfflineGrantMetadata,
} from "./offlineVaultRepository";
import {
  bytesEqual,
  fromBase64Url,
  toBase64Url,
} from "./webauthnCodec";

const CPF_SALT_PATTERN = /^[A-Za-z0-9_-]{22}$/;
const CPF_VERIFIER_PATTERN = /^[A-Za-z0-9_-]{43}$/;
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
  signedGrant: SignedOfflineGrant,
  authenticatedOwnerId: string,
): Promise<OfflineCpfGrantMetadata> {
  const canonicalCpf = requireCanonicalCpf(cpf);
  const verified = await verifySignedOfflineGrant(signedGrant);
  if (verified.claims.colaboradorId !== authenticatedOwnerId) {
    throw new OfflineGrantOwnerMismatchError();
  }
  const existing = await loadCollaborativeOfflineGrant(canonicalCpf);
  const cpfSalt = crypto.getRandomValues(new Uint8Array(CPF_SALT_BYTES));
  const cpfVerifier = await deriveCpfVerifier(canonicalCpf, cpfSalt);
  const metadata: OfflineCpfGrantMetadata = {
    key: existing?.key ?? crypto.randomUUID(),
    versao: 2,
    cpfSalt: toBase64Url(cpfSalt),
    cpfVerifier: toBase64Url(cpfVerifier),
    ownerId: verified.claims.colaboradorId,
    scopeFingerprint: await scopeFingerprint(
      verified.claims.colaboradorId,
      offlineGrantScopeMaterial(verified.claims),
    ),
    signedGrant,
    serverKeyFingerprint: verified.fingerprint,
    atualizadoEm: new Date().toISOString(),
  };
  await saveCollaborativeOfflineGrantMetadata(metadata);
  return metadata;
}

export async function loadCollaborativeOfflineGrant(
  cpf: string,
): Promise<OfflineCpfGrantMetadata | null> {
  const canonicalCpf = requireCanonicalCpf(cpf);
  const candidates = await listCollaborativeOfflineGrantMetadata();
  for (const candidate of candidates.slice(0, MAX_CPF_GRANTS_TO_CHECK)) {
    let normalized: OfflineCpfGrantMetadata;
    try {
      normalized = validateMetadata(candidate);
    } catch {
      continue;
    }
    if (await matchesCpf(canonicalCpf, normalized)) {
      return normalized;
    }
  }
  return null;
}

export async function unlockCollaborativeOfflineGrant(
  cpf: string,
  metadata: OfflineCpfGrantMetadata,
): Promise<void> {
  clearSessionForCurrentDocument();
  const canonicalCpf = requireCanonicalCpf(cpf);
  const normalized = validateMetadata(metadata);
  if (!await matchesCpf(canonicalCpf, normalized)) {
    throw new Error("CPF não corresponde ao grant offline.");
  }
  const verified = await verifySignedOfflineGrant(normalized.signedGrant);
  if (verified.fingerprint !== normalized.serverKeyFingerprint) {
    throw new Error("A assinatura do grant offline não é confiável.");
  }
  const expectedScope = await scopeFingerprint(
    verified.claims.colaboradorId,
    offlineGrantScopeMaterial(verified.claims),
  );
  if (
    normalized.ownerId !== verified.claims.colaboradorId ||
    normalized.scopeFingerprint !== expectedScope
  ) {
    throw new Error("O escopo do grant offline foi alterado.");
  }
  activateOfflineGrant(verified.claims);
}

function requireCanonicalCpf(cpf: string): string {
  const canonical = onlyDigits(cpf);
  if (!/^\d{11}$/.test(canonical)) {
    throw new Error("CPF inválido para o grant offline.");
  }
  return canonical;
}

async function matchesCpf(
  canonicalCpf: string,
  metadata: OfflineCpfGrantMetadata,
): Promise<boolean> {
  const salt = fromBase64Url(metadata.cpfSalt, CPF_SALT_BYTES);
  const expected = fromBase64Url(metadata.cpfVerifier, 32);
  const actual = await deriveCpfVerifier(canonicalCpf, salt);
  return bytesEqual(actual, expected);
}

async function deriveCpfVerifier(
  canonicalCpf: string,
  salt: Uint8Array<ArrayBuffer>,
): Promise<Uint8Array<ArrayBuffer>> {
  const material = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(canonicalCpf),
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
}

function validateMetadata(
  value: OfflineCpfGrantMetadata,
): OfflineCpfGrantMetadata {
  if (
    !isRecord(value) ||
    !exactKeys(value, [
      "atualizadoEm",
      "cpfSalt",
      "cpfVerifier",
      "key",
      "ownerId",
      "scopeFingerprint",
      "serverKeyFingerprint",
      "signedGrant",
      "versao",
    ]) ||
    value.versao !== 2 ||
    !CPF_SALT_PATTERN.test(value.cpfSalt) ||
    !CPF_VERIFIER_PATTERN.test(value.cpfVerifier) ||
    !canonicalUuid(value.key) ||
    !canonicalUuid(value.ownerId) ||
    !/^[0-9a-f]{64}$/.test(value.scopeFingerprint) ||
    !/^[A-Za-z0-9_-]{43}$/.test(value.serverKeyFingerprint) ||
    !Number.isFinite(Date.parse(value.atualizadoEm))
  ) {
    throw new Error("Metadados do grant offline inválidos.");
  }
  return value;
}

function exactKeys(value: Record<string, unknown>, expected: string[]): boolean {
  const keys = Object.keys(value).sort();
  return keys.length === expected.length &&
    keys.every((key, index) => key === expected[index]);
}

function canonicalUuid(value: unknown): value is string {
  return typeof value === "string" &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
