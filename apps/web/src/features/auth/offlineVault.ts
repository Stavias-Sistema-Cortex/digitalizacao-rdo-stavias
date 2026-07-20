import type {
  OfflineGrantClaims,
  OfflineUnlockResult,
  OfflineVaultMetadata,
  SignedOfflineGrant,
} from "./offlineVault.types";
import {
  clearOfflineSession,
  setOfflineSession,
} from "./authSession";
import {
  bytesEqual,
  fromBase64Url,
  toBase64Url,
} from "./webauthnCodec";
import { scopeFingerprint } from "../../lib/db/localDataNamespace";

const MAX_GRANT_SECONDS = 24 * 60 * 60;
const CLOCK_SKEW_MS = 5 * 60 * 1_000;
const VAULT_INFO = new TextEncoder().encode(
  "cortex:offline-vault:aes-gcm:v1",
);
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

type VaultPolicy = {
  allowedKeyFingerprints?: string[];
  now?: () => number;
};

type CreateOfflineVaultInput = VaultPolicy & {
  credentialId: string;
  rpId: string;
  prfSalt: string;
  prfOutput: ArrayBuffer | ArrayBufferView;
  signedGrant: SignedOfflineGrant;
};

let activeGrant: OfflineGrantClaims | null = null;

export async function createOfflineVault(
  input: CreateOfflineVaultInput,
): Promise<OfflineVaultMetadata> {
  const credentialId = exactBytes(input.credentialId, 1, 1_024);
  const prfSalt = exactBytes(input.prfSalt, 32, 32);
  const prfOutput = copyBytes(input.prfOutput);
  if (prfOutput.byteLength !== 32) {
    throw new Error("Resultado PRF inválido para o cofre offline.");
  }
  const rpId = canonicalRpId(input.rpId);
  const verified = await verifySignedGrant(
    input.signedGrant,
    input,
  );
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ownerId = verified.claims.colaboradorId;
  const scopeHash = await scopeFingerprint(
    ownerId,
    grantScopeMaterial(verified.claims),
  );
  const credentialFingerprint = toBase64Url(
    await crypto.subtle.digest("SHA-256", credentialId),
  );
  const metadataBase = {
    key: `${ownerId}:${credentialFingerprint}`,
    versao: 1 as const,
    ownerId,
    scopeFingerprint: scopeHash,
    credentialId: toBase64Url(credentialId),
    rpId,
    prfSalt: toBase64Url(prfSalt),
    serverKeyFingerprint: verified.fingerprint,
  };

  try {
    const key = await deriveVaultKey(prfOutput, prfSalt);
    const plaintext = new TextEncoder().encode(
      JSON.stringify(input.signedGrant),
    );
    const ciphertext = await crypto.subtle.encrypt(
      {
        name: "AES-GCM",
        iv,
        additionalData: vaultAdditionalData(metadataBase),
        tagLength: 128,
      },
      key,
      plaintext,
    );
    return {
      ...metadataBase,
      iv: toBase64Url(iv),
      ciphertext: toBase64Url(ciphertext),
      atualizadoEm: new Date(input.now?.() ?? Date.now()).toISOString(),
    };
  } finally {
    prfOutput.fill(0);
  }
}

export async function unlockOfflineVault(
  metadata: OfflineVaultMetadata,
  policy: VaultPolicy = {},
): Promise<OfflineUnlockResult> {
  const normalized = validateMetadata(metadata);
  if (
    typeof navigator === "undefined" ||
    !navigator.credentials?.get
  ) {
    return "PRF_UNAVAILABLE";
  }
  const prfOutput = await requestVaultPrf(normalized);
  if (!prfOutput) {
    return "PRF_UNAVAILABLE";
  }

  try {
    const key = await deriveVaultKey(
      prfOutput,
      exactBytes(normalized.prfSalt, 32, 32),
    );
    let plaintext: ArrayBuffer;
    try {
      plaintext = await crypto.subtle.decrypt(
        {
          name: "AES-GCM",
          iv: exactBytes(normalized.iv, 12, 12),
          additionalData: vaultAdditionalData(normalized),
          tagLength: 128,
        },
        key,
        fromBase64Url(normalized.ciphertext, 65_536),
      );
    } catch {
      throw new Error("O cofre offline está inválido ou foi alterado.");
    }
    const grant = parseSignedGrant(
      new TextDecoder("utf-8", { fatal: true }).decode(plaintext),
    );
    const verified = await verifySignedGrant(grant, policy);
    if (verified.fingerprint !== normalized.serverKeyFingerprint) {
      throw new Error("A assinatura do grant offline não é confiável.");
    }
    const expectedScope = await scopeFingerprint(
      verified.claims.colaboradorId,
      grantScopeMaterial(verified.claims),
    );
    if (
      normalized.ownerId !== verified.claims.colaboradorId ||
      normalized.scopeFingerprint !== expectedScope
    ) {
      throw new Error("O escopo do cofre offline foi alterado.");
    }
    activeGrant = verified.claims;
    setOfflineSession({
      colaboradorId: verified.claims.colaboradorId,
      nome: verified.claims.nome,
      papelAcesso: verified.claims.papelAcesso,
      escopoGlobal: verified.claims.escopoGlobal,
      obraIds: [...verified.claims.obraIds],
      expiraEm: verified.claims.expiraEm,
    });
    return "UNLOCKED";
  } finally {
    prfOutput.fill(0);
  }
}

export async function renewOfflineVault(
  metadata: OfflineVaultMetadata,
  requestGrant: () => Promise<SignedOfflineGrant>,
  policy: VaultPolicy = {},
): Promise<OfflineVaultMetadata | "PRF_UNAVAILABLE"> {
  const normalized = validateMetadata(metadata);
  if (
    typeof navigator === "undefined" ||
    !navigator.credentials?.get
  ) {
    return "PRF_UNAVAILABLE";
  }
  const prfOutput = await requestVaultPrf(normalized);
  if (!prfOutput) {
    return "PRF_UNAVAILABLE";
  }
  try {
    const signedGrant = await requestGrant();
    return await createOfflineVault({
      credentialId: normalized.credentialId,
      rpId: normalized.rpId,
      prfSalt: normalized.prfSalt,
      prfOutput,
      signedGrant,
      ...policy,
    });
  } finally {
    prfOutput.fill(0);
  }
}

export function getOfflineGrant(): OfflineGrantClaims | null {
  if (activeGrant && Date.parse(activeGrant.expiraEm) <= Date.now()) {
    clearOfflineGrant();
  }
  return activeGrant
    ? { ...activeGrant, obraIds: [...activeGrant.obraIds] }
    : null;
}

export function clearOfflineGrant(): void {
  activeGrant = null;
  clearOfflineSession();
}

async function verifySignedGrant(
  grant: SignedOfflineGrant,
  policy: VaultPolicy,
): Promise<{ claims: OfflineGrantClaims; fingerprint: string }> {
  validateEnvelope(grant);
  const payload = fromBase64Url(grant.payload, 32_768);
  const publicKeySpki = fromBase64Url(grant.publicKeySpki, 8_192);
  const signature = fromBase64Url(grant.signature, 8_192);
  const fingerprint = toBase64Url(
    await crypto.subtle.digest("SHA-256", publicKeySpki),
  );
  const allowed = allowedFingerprints(policy, fingerprint);
  if (!allowed.has(fingerprint)) {
    throw new Error("A assinatura do grant offline não é confiável.");
  }

  let publicKey: CryptoKey;
  try {
    publicKey = await crypto.subtle.importKey(
      "spki",
      publicKeySpki,
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["verify"],
    );
  } catch {
    throw new Error("A chave de assinatura do grant offline é inválida.");
  }
  const valid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    publicKey,
    signature,
    payload,
  );
  if (!valid) {
    throw new Error("A assinatura do grant offline é inválida.");
  }
  const claims = parseClaims(
    new TextDecoder("utf-8", { fatal: true }).decode(payload),
    policy.now?.() ?? Date.now(),
  );
  return { claims, fingerprint };
}

function allowedFingerprints(
  policy: VaultPolicy,
  localFingerprint: string,
): Set<string> {
  const configured = policy.allowedKeyFingerprints ??
    import.meta.env.VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256
      ?.split(",")
      .map((value) => value.trim())
      .filter(Boolean) ?? [];
  if (configured.some((value) => !/^[A-Za-z0-9_-]{43}$/.test(value))) {
    throw new Error("Configuração da assinatura offline inválida.");
  }
  if (configured.length > 0) {
    return new Set(configured);
  }
  if (import.meta.env.PROD) {
    throw new Error("A assinatura do grant offline não foi configurada.");
  }
  return new Set([localFingerprint]);
}

async function deriveVaultKey(
  prfOutput: Uint8Array<ArrayBuffer>,
  prfSalt: Uint8Array<ArrayBuffer>,
): Promise<CryptoKey> {
  const sourceKey = await crypto.subtle.importKey(
    "raw",
    prfOutput,
    "HKDF",
    false,
    ["deriveKey"],
  );
  return crypto.subtle.deriveKey(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: prfSalt,
      info: VAULT_INFO,
    },
    sourceKey,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

function extractPrfFirst(
  extensions: AuthenticationExtensionsClientOutputs,
): Uint8Array<ArrayBuffer> | null {
  const prf = (extensions as Record<string, unknown>).prf;
  if (!isRecord(prf)) {
    return null;
  }
  const results = prf.results;
  if (!isRecord(results)) {
    return null;
  }
  const first = results.first;
  if (first instanceof ArrayBuffer) {
    const copy = new Uint8Array(first.slice(0));
    return copy.byteLength === 32 ? copy : null;
  }
  if (ArrayBuffer.isView(first)) {
    const copy = new Uint8Array(
      first.buffer.slice(
        first.byteOffset,
        first.byteOffset + first.byteLength,
      ) as ArrayBuffer,
    );
    return copy.byteLength === 32 ? copy : null;
  }
  return null;
}

async function requestVaultPrf(
  metadata: OfflineVaultMetadata,
): Promise<Uint8Array<ArrayBuffer> | null> {
  const credentialId = fromBase64Url(metadata.credentialId, 1_024);
  const prfSalt = exactBytes(metadata.prfSalt, 32, 32);
  const challenge = crypto.getRandomValues(new Uint8Array(32));
  let credential: Credential | null;
  try {
    credential = await navigator.credentials.get({
      publicKey: {
        challenge,
        rpId: metadata.rpId,
        allowCredentials: [
          { id: credentialId, type: "public-key" },
        ],
        userVerification: "required",
        extensions: {
          prf: { eval: { first: prfSalt } },
        },
      } as PublicKeyCredentialRequestOptions,
    });
  } catch (error: unknown) {
    if (
      error instanceof DOMException &&
      error.name === "NotSupportedError"
    ) {
      return null;
    }
    throw error;
  } finally {
    challenge.fill(0);
  }
  if (!credential || credential.type !== "public-key") {
    throw new Error("A credencial do cofre offline não foi apresentada.");
  }
  const publicCredential = credential as PublicKeyCredential;
  if (
    !bytesEqual(new Uint8Array(publicCredential.rawId), credentialId)
  ) {
    throw new Error("A credencial apresentada não pertence a este cofre.");
  }
  return extractPrfFirst(publicCredential.getClientExtensionResults());
}

function validateEnvelope(grant: SignedOfflineGrant): void {
  if (
    !isRecord(grant) ||
    !exactKeys(grant, ["keyId", "payload", "publicKeySpki", "signature"]) ||
    !boundedString(grant.keyId, 1, 80) ||
    !boundedString(grant.payload, 1, 50_000) ||
    !boundedString(grant.signature, 1, 12_000) ||
    !boundedString(grant.publicKeySpki, 1, 12_000)
  ) {
    throw new Error("Envelope do grant offline inválido.");
  }
}

function parseSignedGrant(value: string): SignedOfflineGrant {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    throw new Error("Envelope do grant offline inválido.");
  }
  validateEnvelope(parsed as SignedOfflineGrant);
  return parsed as SignedOfflineGrant;
}

function parseClaims(value: string, now: number): OfflineGrantClaims {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    throw new Error("Grant offline inválido.");
  }
  if (
    !isRecord(parsed) ||
    !exactKeys(parsed, [
      "colaboradorId",
      "emitidoEm",
      "escopoGlobal",
      "expiraEm",
      "nome",
      "obraIds",
      "papelAcesso",
      "versao",
    ]) ||
    parsed.versao !== 1 ||
    !canonicalUuid(parsed.colaboradorId) ||
    !boundedString(parsed.nome, 1, 255) ||
    (parsed.papelAcesso !== "ALFA" && parsed.papelAcesso !== "BETA") ||
    typeof parsed.escopoGlobal !== "boolean" ||
    !Array.isArray(parsed.obraIds) ||
    parsed.obraIds.length > 10_000 ||
    !parsed.obraIds.every(canonicalUuid) ||
    !isSortedUnique(parsed.obraIds) ||
    !boundedString(parsed.emitidoEm, 1, 40) ||
    !boundedString(parsed.expiraEm, 1, 40)
  ) {
    throw new Error("Grant offline inválido.");
  }
  if (
    (parsed.papelAcesso === "ALFA" &&
      (!parsed.escopoGlobal || parsed.obraIds.length !== 0)) ||
    (parsed.papelAcesso === "BETA" && parsed.escopoGlobal)
  ) {
    throw new Error("Escopo do grant offline inválido.");
  }
  const issuedAt = Date.parse(parsed.emitidoEm);
  const expiresAt = Date.parse(parsed.expiraEm);
  if (
    !Number.isFinite(issuedAt) ||
    !Number.isFinite(expiresAt) ||
    issuedAt > now + CLOCK_SKEW_MS ||
    expiresAt <= now ||
    expiresAt <= issuedAt ||
    expiresAt - issuedAt > MAX_GRANT_SECONDS * 1_000
  ) {
    throw new Error("O grant offline expirou ou possui validade inválida.");
  }
  return parsed as OfflineGrantClaims;
}

function validateMetadata(
  value: OfflineVaultMetadata,
): OfflineVaultMetadata {
  if (
    !isRecord(value) ||
    !exactKeys(value, [
      "atualizadoEm",
      "ciphertext",
      "credentialId",
      "iv",
      "key",
      "ownerId",
      "prfSalt",
      "rpId",
      "scopeFingerprint",
      "serverKeyFingerprint",
      "versao",
    ]) ||
    value.versao !== 1 ||
    !boundedString(value.key, 1, 1_600) ||
    !canonicalUuid(value.ownerId) ||
    !/^[0-9a-f]{64}$/.test(value.scopeFingerprint) ||
    !boundedString(value.credentialId, 1, 1_400) ||
    !boundedString(value.prfSalt, 43, 43) ||
    !boundedString(value.iv, 16, 16) ||
    !boundedString(value.ciphertext, 1, 90_000) ||
    !/^[A-Za-z0-9_-]{43}$/.test(value.serverKeyFingerprint) ||
    !Number.isFinite(Date.parse(value.atualizadoEm))
  ) {
    throw new Error("Metadados do cofre offline inválidos.");
  }
  canonicalRpId(value.rpId);
  exactBytes(value.credentialId, 1, 1_024);
  exactBytes(value.prfSalt, 32, 32);
  exactBytes(value.iv, 12, 12);
  fromBase64Url(value.ciphertext, 65_536);
  return value;
}

function vaultAdditionalData(value: {
  key: string;
  versao: 1;
  ownerId: string;
  scopeFingerprint: string;
  credentialId: string;
  rpId: string;
  prfSalt: string;
  serverKeyFingerprint: string;
}): Uint8Array<ArrayBuffer> {
  return new TextEncoder().encode(
    JSON.stringify({
      key: value.key,
      versao: value.versao,
      ownerId: value.ownerId,
      scopeFingerprint: value.scopeFingerprint,
      credentialId: value.credentialId,
      rpId: value.rpId,
      prfSalt: value.prfSalt,
      serverKeyFingerprint: value.serverKeyFingerprint,
    }),
  );
}

function grantScopeMaterial(claims: OfflineGrantClaims): string {
  return claims.escopoGlobal
    ? "ALFA:GLOBAL"
    : `BETA:${claims.obraIds.join(",")}`;
}

function exactBytes(
  value: string,
  minimum: number,
  maximum: number,
): Uint8Array<ArrayBuffer> {
  const bytes = fromBase64Url(value, maximum);
  if (bytes.byteLength < minimum || bytes.byteLength > maximum) {
    throw new Error("Metadados do cofre offline inválidos.");
  }
  return bytes;
}

function copyBytes(
  value: ArrayBuffer | ArrayBufferView,
): Uint8Array<ArrayBuffer> {
  const source = value instanceof ArrayBuffer
    ? new Uint8Array(value)
    : new Uint8Array(
        value.buffer,
        value.byteOffset,
        value.byteLength,
      );
  return new Uint8Array(source);
}

function canonicalRpId(value: unknown): string {
  if (!boundedString(value, 1, 253) || value !== value.toLowerCase()) {
    throw new Error("RP ID do cofre offline inválido.");
  }
  try {
    const parsed = new URL(`https://${value}`);
    if (
      parsed.hostname !== value ||
      parsed.pathname !== "/" ||
      parsed.port ||
      parsed.username ||
      parsed.password
    ) {
      throw new Error();
    }
  } catch {
    throw new Error("RP ID do cofre offline inválido.");
  }
  return value;
}

function exactKeys(
  value: Record<string, unknown>,
  expected: string[],
): boolean {
  const keys = Object.keys(value).sort();
  return keys.length === expected.length &&
    keys.every((key, index) => key === expected[index]);
}

function isSortedUnique(values: unknown[]): values is string[] {
  for (let index = 0; index < values.length; index += 1) {
    const current = values[index];
    const previous = index > 0 ? values[index - 1] : null;
    if (
      typeof current !== "string" ||
      (index > 0 &&
        (typeof previous !== "string" || previous >= current))
    ) {
      return false;
    }
  }
  return true;
}

function canonicalUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value);
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
