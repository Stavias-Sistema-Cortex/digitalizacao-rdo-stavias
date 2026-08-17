import { scopeFingerprint } from "../../lib/db/localDataNamespace";
import { onlyDigits } from "./loginValidation";
import {
  offlineGrantScopeMaterial,
  parseSignedOfflineGrant,
  verifySignedOfflineGrant,
} from "./offlineVault";
import type {
  CurrentOfflineGrantClaims,
  OfflinePasswordVaultFailureState,
  OfflinePasswordVaultMetadata,
  SignedOfflineGrant,
} from "./offlineVault.types";
import {
  bytesEqual,
  fromBase64Url,
  toBase64Url,
} from "./webauthnCodec";

const KDF_ITERATIONS = 600_000 as const;
const SALT_BYTES = 16;
const IV_BYTES = 12;
const FAILURE_WINDOW_MS = 15 * 60 * 1_000;
const MAX_FAILURES = 5;
const BASE_DELAY_MS = 1_000;
const CLOCK_SKEW_MS = 5 * 60 * 1_000;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const EMPTY_FAILURE_STATE: OfflinePasswordVaultFailureState = {
  windowStartedAt: null,
  failures: 0,
  blockedUntil: null,
};

type PasswordVaultPlaintext = {
  signedGrant: SignedOfflineGrant;
  ownerId: string;
  scopeFingerprint: string;
  authEpoch: number;
  lastTrustedTime: string;
};

export type OpenPasswordOfflineVaultResult =
  | {
      kind: "UNLOCKED";
      claims: CurrentOfflineGrantClaims;
      metadata: OfflinePasswordVaultMetadata;
    }
  | {
      kind: "REJECTED";
      metadata: OfflinePasswordVaultMetadata;
    };

const liveKeys = new Map<string, CryptoKey>();

export class OfflinePasswordVaultUnlockError extends Error {
  constructor(options?: ErrorOptions) {
    super(
      "Não foi possível liberar o acesso offline neste aparelho.",
      options,
    );
    this.name = "OfflinePasswordVaultUnlockError";
  }
}

export async function createPasswordOfflineVault(input: {
  cpf: string;
  password: string;
  signedGrant: SignedOfflineGrant;
  authenticatedOwnerId: string;
  now?: () => number;
}): Promise<OfflinePasswordVaultMetadata> {
  const canonicalCpf = requireCanonicalCpf(input.cpf);
  requirePassword(input.password);
  const now = requireNow(input.now?.() ?? Date.now());
  const verified = await verifySignedOfflineGrant(input.signedGrant, {
    now: () => now,
  });
  const claims = requireCurrentClaims(verified.claims);
  if (
    !UUID_PATTERN.test(input.authenticatedOwnerId) ||
    claims.colaboradorId !== input.authenticatedOwnerId
  ) {
    throw new Error(
      "O grant offline não corresponde à identidade autenticada.",
    );
  }

  const cpfSalt = randomBytes(SALT_BYTES);
  const passwordSalt = randomBytes(SALT_BYTES);
  const cpfVerifier = await deriveCpfVerifier(canonicalCpf, cpfSalt);
  const key = await derivePasswordKey(input.password, passwordSalt);
  const metadataBase = {
    key: crypto.randomUUID(),
    versao: 3 as const,
    cpfSalt: toBase64Url(cpfSalt),
    cpfVerifier: toBase64Url(cpfVerifier),
    passwordSalt: toBase64Url(passwordSalt),
    kdf: "PBKDF2-SHA256" as const,
    kdfIterations: KDF_ITERATIONS,
    serverKeyFingerprint: verified.fingerprint,
  };
  const plaintext: PasswordVaultPlaintext = {
    signedGrant: input.signedGrant,
    ownerId: claims.colaboradorId,
    scopeFingerprint: await scopeFingerprint(
      claims.colaboradorId,
      offlineGrantScopeMaterial(claims),
    ),
    authEpoch: claims.authEpoch,
    lastTrustedTime: new Date(now).toISOString(),
  };
  const metadata = await seal(
    metadataBase,
    plaintext,
    key,
    now,
    EMPTY_FAILURE_STATE,
  );
  liveKeys.set(metadata.key, key);
  return metadata;
}

export async function openPasswordOfflineVault(input: {
  cpf: string;
  password: string;
  metadata: OfflinePasswordVaultMetadata;
  now?: () => number;
}): Promise<OpenPasswordOfflineVaultResult> {
  const metadata = validatePasswordVaultMetadata(input.metadata);
  const now = requireNow(input.now?.() ?? Date.now());
  const available = failureStateAt(metadata.failedAttemptState, now);
  if (
    available.blockedUntil !== null &&
    now < Date.parse(available.blockedUntil)
  ) {
    return {
      kind: "REJECTED",
      metadata: { ...metadata, failedAttemptState: available },
    };
  }

  try {
    const canonicalCpf = requireCanonicalCpf(input.cpf);
    requirePassword(input.password);
    const actualVerifier = await deriveCpfVerifier(
      canonicalCpf,
      exactBytes(metadata.cpfSalt, SALT_BYTES),
    );
    const expectedVerifier = exactBytes(metadata.cpfVerifier, 32);
    if (!bytesEqual(actualVerifier, expectedVerifier)) {
      return rejected(metadata, available, now);
    }

    const key = await derivePasswordKey(
      input.password,
      exactBytes(metadata.passwordSalt, SALT_BYTES),
    );
    const plaintext = await decrypt(metadata, key);
    const verified = await verifySignedOfflineGrant(plaintext.signedGrant, {
      allowedKeyFingerprints: [metadata.serverKeyFingerprint],
      now: () => now,
    });
    const claims = requireCurrentClaims(verified.claims);
    const expectedScope = await scopeFingerprint(
      claims.colaboradorId,
      offlineGrantScopeMaterial(claims),
    );
    const lastTrustedTime = Date.parse(plaintext.lastTrustedTime);
    if (
      verified.fingerprint !== metadata.serverKeyFingerprint ||
      plaintext.ownerId !== claims.colaboradorId ||
      plaintext.scopeFingerprint !== expectedScope ||
      plaintext.authEpoch !== claims.authEpoch ||
      !Number.isFinite(lastTrustedTime) ||
      now + CLOCK_SKEW_MS < lastTrustedTime
    ) {
      return rejected(metadata, available, now);
    }

    const updatedPlaintext: PasswordVaultPlaintext = {
      ...plaintext,
      lastTrustedTime: new Date(Math.max(lastTrustedTime, now)).toISOString(),
    };
    const updated = await seal(
      metadata,
      updatedPlaintext,
      key,
      now,
      EMPTY_FAILURE_STATE,
    );
    liveKeys.set(updated.key, key);
    return { kind: "UNLOCKED", claims, metadata: updated };
  } catch {
    return rejected(metadata, available, now);
  }
}

export async function resealPasswordOfflineVault(
  metadataValue: OfflinePasswordVaultMetadata,
  signedGrant: SignedOfflineGrant,
  authenticatedOwnerId: string,
  nowSource: () => number = Date.now,
): Promise<OfflinePasswordVaultMetadata | "PASSWORD_REQUIRED"> {
  const metadata = validatePasswordVaultMetadata(metadataValue);
  const key = liveKeys.get(metadata.key);
  if (!key) {
    return "PASSWORD_REQUIRED";
  }
  const now = requireNow(nowSource());
  const previous = await decrypt(metadata, key);
  const verified = await verifySignedOfflineGrant(signedGrant, {
    now: () => now,
  });
  const claims = requireCurrentClaims(verified.claims);
  const lastTrustedTime = Date.parse(previous.lastTrustedTime);
  if (
    !UUID_PATTERN.test(authenticatedOwnerId) ||
    previous.ownerId !== authenticatedOwnerId ||
    claims.colaboradorId !== authenticatedOwnerId ||
    !Number.isFinite(lastTrustedTime) ||
    now + CLOCK_SKEW_MS < lastTrustedTime
  ) {
    throw new OfflinePasswordVaultUnlockError();
  }
  const updated: PasswordVaultPlaintext = {
    signedGrant,
    ownerId: claims.colaboradorId,
    scopeFingerprint: await scopeFingerprint(
      claims.colaboradorId,
      offlineGrantScopeMaterial(claims),
    ),
    authEpoch: claims.authEpoch,
    lastTrustedTime: new Date(Math.max(lastTrustedTime, now)).toISOString(),
  };
  const resealed = await seal(
    { ...metadata, serverKeyFingerprint: verified.fingerprint },
    updated,
    key,
    now,
    EMPTY_FAILURE_STATE,
  );
  liveKeys.set(resealed.key, key);
  return resealed;
}

export function hasLivePasswordVaultKey(
  metadata: OfflinePasswordVaultMetadata,
): boolean {
  return liveKeys.has(metadata.key);
}

export function clearPasswordVaultKeys(): void {
  liveKeys.clear();
}

export async function matchesPasswordVaultCpf(
  cpf: string,
  metadataValue: OfflinePasswordVaultMetadata,
): Promise<boolean> {
  try {
    const metadata = validatePasswordVaultMetadata(metadataValue);
    const canonicalCpf = requireCanonicalCpf(cpf);
    const actual = await deriveCpfVerifier(
      canonicalCpf,
      exactBytes(metadata.cpfSalt, SALT_BYTES),
    );
    try {
      return bytesEqual(actual, exactBytes(metadata.cpfVerifier, 32));
    } finally {
      actual.fill(0);
    }
  } catch {
    return false;
  }
}

export function isOfflinePasswordVaultMetadata(
  value: unknown,
): value is OfflinePasswordVaultMetadata {
  try {
    validatePasswordVaultMetadata(value);
    return true;
  } catch {
    return false;
  }
}

function validatePasswordVaultMetadata(
  value: unknown,
): OfflinePasswordVaultMetadata {
  if (
    !isRecord(value) ||
    !exactKeys(value, [
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
    ]) ||
    value.versao !== 3 ||
    value.kdf !== "PBKDF2-SHA256" ||
    value.kdfIterations !== KDF_ITERATIONS ||
    !UUID_PATTERN.test(String(value.key)) ||
    !boundedString(value.cpfSalt, 22, 22) ||
    !boundedString(value.cpfVerifier, 43, 43) ||
    !boundedString(value.passwordSalt, 22, 22) ||
    !boundedString(value.iv, 16, 16) ||
    !boundedString(value.ciphertext, 1, 90_000) ||
    !boundedString(value.serverKeyFingerprint, 43, 43) ||
    !boundedString(value.atualizadoEm, 1, 40) ||
    !Number.isFinite(Date.parse(value.atualizadoEm)) ||
    !validFailureState(value.failedAttemptState)
  ) {
    throw new OfflinePasswordVaultUnlockError();
  }
  exactBytes(value.cpfSalt, SALT_BYTES);
  exactBytes(value.cpfVerifier, 32);
  exactBytes(value.passwordSalt, SALT_BYTES);
  exactBytes(value.iv, IV_BYTES);
  fromBase64Url(value.ciphertext, 65_536);
  exactBytes(value.serverKeyFingerprint, 32);
  return value as OfflinePasswordVaultMetadata;
}

async function seal(
  metadata: Omit<OfflinePasswordVaultMetadata, "iv" | "ciphertext" | "atualizadoEm" | "failedAttemptState">,
  plaintext: PasswordVaultPlaintext,
  key: CryptoKey,
  now: number,
  failedAttemptState: OfflinePasswordVaultFailureState,
): Promise<OfflinePasswordVaultMetadata> {
  const iv = randomBytes(IV_BYTES);
  const plaintextBytes = new TextEncoder().encode(JSON.stringify(plaintext));
  try {
    const ciphertext = await crypto.subtle.encrypt(
      {
        name: "AES-GCM",
        iv,
        additionalData: additionalData(metadata),
        tagLength: 128,
      },
      key,
      plaintextBytes,
    );
    return {
      key: metadata.key,
      versao: 3,
      cpfSalt: metadata.cpfSalt,
      cpfVerifier: metadata.cpfVerifier,
      passwordSalt: metadata.passwordSalt,
      kdf: "PBKDF2-SHA256",
      kdfIterations: KDF_ITERATIONS,
      iv: toBase64Url(iv),
      ciphertext: toBase64Url(ciphertext),
      serverKeyFingerprint: metadata.serverKeyFingerprint,
      atualizadoEm: new Date(now).toISOString(),
      failedAttemptState: { ...failedAttemptState },
    };
  } finally {
    plaintextBytes.fill(0);
  }
}

async function decrypt(
  metadata: OfflinePasswordVaultMetadata,
  key: CryptoKey,
): Promise<PasswordVaultPlaintext> {
  const plaintext = new Uint8Array(await crypto.subtle.decrypt(
    {
      name: "AES-GCM",
      iv: exactBytes(metadata.iv, IV_BYTES),
      additionalData: additionalData(metadata),
      tagLength: 128,
    },
    key,
    fromBase64Url(metadata.ciphertext, 65_536),
  ));
  try {
    const decoded = new TextDecoder("utf-8", { fatal: true }).decode(plaintext);
    return parsePlaintext(decoded);
  } finally {
    plaintext.fill(0);
  }
}

function parsePlaintext(value: string): PasswordVaultPlaintext {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    throw new OfflinePasswordVaultUnlockError();
  }
  if (
    !isRecord(parsed) ||
    !exactKeys(parsed, [
      "authEpoch",
      "lastTrustedTime",
      "ownerId",
      "scopeFingerprint",
      "signedGrant",
    ]) ||
    !UUID_PATTERN.test(String(parsed.ownerId)) ||
    !/^[0-9a-f]{64}$/.test(String(parsed.scopeFingerprint)) ||
    typeof parsed.authEpoch !== "number" ||
    !Number.isSafeInteger(parsed.authEpoch) ||
    parsed.authEpoch < 1 ||
    !boundedString(parsed.lastTrustedTime, 1, 40) ||
    !Number.isFinite(Date.parse(parsed.lastTrustedTime))
  ) {
    throw new OfflinePasswordVaultUnlockError();
  }
  return {
    signedGrant: parseSignedOfflineGrant(parsed.signedGrant),
    ownerId: parsed.ownerId as string,
    scopeFingerprint: parsed.scopeFingerprint as string,
    authEpoch: parsed.authEpoch,
    lastTrustedTime: parsed.lastTrustedTime,
  };
}

function additionalData(value: {
  key: string;
  versao: 3;
  cpfVerifier: string;
  serverKeyFingerprint: string;
  kdf: "PBKDF2-SHA256";
  kdfIterations: 600_000;
}): Uint8Array<ArrayBuffer> {
  return new TextEncoder().encode(JSON.stringify({
    key: value.key,
    versao: value.versao,
    cpfVerifier: value.cpfVerifier,
    serverKeyFingerprint: value.serverKeyFingerprint,
    kdf: value.kdf,
    kdfIterations: value.kdfIterations,
  }));
}

async function deriveCpfVerifier(
  cpf: string,
  salt: Uint8Array<ArrayBuffer>,
): Promise<Uint8Array<ArrayBuffer>> {
  const bytes = new TextEncoder().encode(cpf);
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
        iterations: KDF_ITERATIONS,
      },
      material,
      256,
    ));
  } finally {
    bytes.fill(0);
  }
}

async function derivePasswordKey(
  password: string,
  salt: Uint8Array<ArrayBuffer>,
): Promise<CryptoKey> {
  const bytes = new TextEncoder().encode(password);
  try {
    const material = await crypto.subtle.importKey(
      "raw",
      bytes,
      "PBKDF2",
      false,
      ["deriveKey"],
    );
    return crypto.subtle.deriveKey(
      {
        name: "PBKDF2",
        hash: "SHA-256",
        salt,
        iterations: KDF_ITERATIONS,
      },
      material,
      { name: "AES-GCM", length: 256 },
      false,
      ["encrypt", "decrypt"],
    );
  } finally {
    bytes.fill(0);
  }
}

function rejected(
  metadata: OfflinePasswordVaultMetadata,
  state: OfflinePasswordVaultFailureState,
  now: number,
): OpenPasswordOfflineVaultResult {
  const windowStartedAt = state.windowStartedAt ?? new Date(now).toISOString();
  const windowEnd = Date.parse(windowStartedAt) + FAILURE_WINDOW_MS;
  const failures = Math.min(state.failures + 1, MAX_FAILURES);
  const delayEnd = now + BASE_DELAY_MS * 2 ** (failures - 1);
  const blockedUntil = failures >= MAX_FAILURES
    ? windowEnd
    : Math.min(delayEnd, windowEnd);
  return {
    kind: "REJECTED",
    metadata: {
      ...metadata,
      atualizadoEm: new Date(now).toISOString(),
      failedAttemptState: {
        windowStartedAt,
        failures,
        blockedUntil: new Date(blockedUntil).toISOString(),
      },
    },
  };
}

function failureStateAt(
  state: OfflinePasswordVaultFailureState,
  now: number,
): OfflinePasswordVaultFailureState {
  if (
    state.windowStartedAt !== null &&
    now >= Date.parse(state.windowStartedAt) + FAILURE_WINDOW_MS
  ) {
    return { ...EMPTY_FAILURE_STATE };
  }
  return { ...state };
}

function validFailureState(value: unknown): boolean {
  if (
    !isRecord(value) ||
    !exactKeys(value, ["blockedUntil", "failures", "windowStartedAt"]) ||
    typeof value.failures !== "number" ||
    !Number.isInteger(value.failures) ||
    value.failures < 0 ||
    value.failures > MAX_FAILURES
  ) {
    return false;
  }
  if (value.failures === 0) {
    return value.windowStartedAt === null && value.blockedUntil === null;
  }
  return validTimestamp(value.windowStartedAt) &&
    validTimestamp(value.blockedUntil) &&
    Date.parse(value.blockedUntil) >= Date.parse(value.windowStartedAt);
}

function validTimestamp(value: unknown): value is string {
  return boundedString(value, 1, 40) && Number.isFinite(Date.parse(value));
}

function requireCurrentClaims(
  claims: { versao: 1 } | CurrentOfflineGrantClaims,
): CurrentOfflineGrantClaims {
  if (claims.versao !== 2) {
    throw new OfflinePasswordVaultUnlockError();
  }
  return claims;
}

function requireCanonicalCpf(cpf: string): string {
  const canonical = onlyDigits(cpf);
  if (!/^\d{11}$/.test(canonical)) {
    throw new OfflinePasswordVaultUnlockError();
  }
  return canonical;
}

function requirePassword(password: string): void {
  if (
    typeof password !== "string" ||
    password.length < 12 ||
    password.length > 256
  ) {
    throw new OfflinePasswordVaultUnlockError();
  }
}

function requireNow(value: number): number {
  if (!Number.isFinite(value)) {
    throw new OfflinePasswordVaultUnlockError();
  }
  return value;
}

function exactBytes(
  value: string,
  expected: number,
): Uint8Array<ArrayBuffer> {
  const bytes = fromBase64Url(value, expected);
  if (bytes.byteLength !== expected) {
    throw new OfflinePasswordVaultUnlockError();
  }
  return bytes;
}

function randomBytes(length: number): Uint8Array<ArrayBuffer> {
  return crypto.getRandomValues(new Uint8Array(length));
}

function exactKeys(value: Record<string, unknown>, expected: string[]): boolean {
  const keys = Object.keys(value).sort();
  return keys.length === expected.length &&
    keys.every((key, index) => key === expected[index]);
}

function boundedString(
  value: unknown,
  minimum: number,
  maximum: number,
): value is string {
  return typeof value === "string" &&
    value.length >= minimum &&
    value.length <= maximum &&
    !value.includes("\r") &&
    !value.includes("\n");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
