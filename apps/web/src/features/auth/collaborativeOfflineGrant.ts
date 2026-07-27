import { scopeFingerprint } from "../../lib/db/localDataNamespace";
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
  loadCollaborativeOfflineGrantMetadata,
  saveCollaborativeOfflineGrantMetadata,
} from "./offlineVaultRepository";

const CPF_HASH_PATTERN = /^[0-9a-f]{64}$/;

export async function saveCollaborativeOfflineGrant(
  cpf: string,
  signedGrant: SignedOfflineGrant,
): Promise<OfflineCpfGrantMetadata> {
  const cpfHash = await hashCanonicalCpf(cpf);
  const verified = await verifySignedOfflineGrant(signedGrant);
  const metadata: OfflineCpfGrantMetadata = {
    key: cpfHash,
    versao: 1,
    cpfHash,
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
  return loadCollaborativeOfflineGrantMetadata(await hashCanonicalCpf(cpf));
}

export async function unlockCollaborativeOfflineGrant(
  cpf: string,
  metadata: OfflineCpfGrantMetadata,
): Promise<void> {
  const cpfHash = await hashCanonicalCpf(cpf);
  const normalized = validateMetadata(metadata);
  if (normalized.cpfHash !== cpfHash) {
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

async function hashCanonicalCpf(cpf: string): Promise<string> {
  const canonical = onlyDigits(cpf);
  if (!/^\d{11}$/.test(canonical)) {
    throw new Error("CPF inválido para o grant offline.");
  }
  const digest = new Uint8Array(
    await crypto.subtle.digest("SHA-256", new TextEncoder().encode(canonical)),
  );
  return Array.from(digest, (value) => value.toString(16).padStart(2, "0"))
    .join("");
}

function validateMetadata(
  value: OfflineCpfGrantMetadata,
): OfflineCpfGrantMetadata {
  if (
    !isRecord(value) ||
    !exactKeys(value, [
      "atualizadoEm",
      "cpfHash",
      "key",
      "ownerId",
      "scopeFingerprint",
      "serverKeyFingerprint",
      "signedGrant",
      "versao",
    ]) ||
    value.versao !== 1 ||
    !CPF_HASH_PATTERN.test(value.cpfHash) ||
    value.key !== value.cpfHash ||
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
