import {
  apiFetch,
  freshAuthenticationFetch,
  readResponseBody,
  responseErrorMessage,
  type ApiRequestOptions,
} from "../../lib/api/apiClient";
import { createOfflineVault, renewOfflineVault } from "./offlineVault";
import type {
  OfflineVaultMetadata,
  SignedOfflineGrant,
} from "./offlineVault.types";
import { saveOfflineVaultMetadata } from "./offlineVaultRepository";
import {
  clearSessionForCurrentDocument,
  setSession,
  type AuthProfile,
} from "./authSession";
import { LIMITE_DE_OBRAS_NO_ESCOPO } from "./escopoDeObras";
import { clearRemoteSessionIsolation } from "./remoteSessionIsolation";
import {
  assertionCredentialToJson,
  creationPrfSalt,
  decodeCreationOptions,
  decodeRequestOptions,
  extractPrfResult,
  registrationCredentialToJson,
  toBase64Url,
} from "./webauthnCodec";

type WebAuthnOptionsResponse = {
  challengeId: string;
  rpId: string;
  publicKey: unknown;
};

type PostTransport = (
  path: string,
  options: ApiRequestOptions,
) => Promise<Response>;

export type PasskeySummary = {
  credentialId: string;
  name: string;
  createdAt: string;
  prfSupported: boolean;
};

export type PasskeyRegistrationResult = {
  summary: PasskeySummary;
  offlineVault: "READY" | "PRF_UNAVAILABLE";
};

export async function registerPasskey(): Promise<PasskeyRegistrationResult> {
  if (!navigator.credentials?.create) {
    throw new Error("Este navegador não permite registrar passkeys.");
  }
  const started = await requestOptions(
    "/auth/passkeys/registration/options",
  );
  const publicKey = decodeCreationOptions(started.publicKey);
  if (publicKey.rp.id !== started.rpId) {
    throw new Error("Configuração RP da passkey divergente.");
  }
  const prfSalt = creationPrfSalt(publicKey);
  const credential = await navigator.credentials.create({ publicKey });
  if (!credential || credential.type !== "public-key") {
    throw new Error("O registro da passkey foi cancelado.");
  }
  const publicCredential = credential as PublicKeyCredential;
  const prfOutput = extractPrfResult(publicCredential);
  try {
    const response = await postJson(
      "/auth/passkeys/registration/verify",
      {
        challengeId: started.challengeId,
        credential: registrationCredentialToJson(publicCredential),
      },
    );
    const summary = parsePasskeySummary(response);
    if (summary.credentialId !== publicCredential.id) {
      throw new Error("A passkey confirmada não corresponde ao registro local.");
    }
    if (!prfSalt || !prfOutput || !summary.prfSupported) {
      return { summary, offlineVault: "PRF_UNAVAILABLE" };
    }
    const signedGrant = parseSignedGrant(
      await postJson("/auth/offline-grant", null),
    );
    const metadata = await createOfflineVault({
      credentialId: publicCredential.id,
      rpId: started.rpId,
      prfSalt: toBase64Url(prfSalt),
      prfOutput,
      signedGrant,
    });
    await saveOfflineVaultMetadata(metadata);
    return { summary, offlineVault: "READY" };
  } finally {
    prfOutput?.fill(0);
    prfSalt?.fill(0);
  }
}

export async function authenticateWithPasskey(
  cpf: string,
): Promise<AuthProfile> {
  if (!navigator.credentials?.get) {
    throw new Error("Este navegador não permite usar passkeys.");
  }
  const started = await requestOptions(
    "/auth/passkeys/authentication/options",
    { cpf },
    freshAuthenticationFetch,
  );
  const publicKey = decodeRequestOptions(started.publicKey);
  if (publicKey.rpId !== started.rpId) {
    throw new Error("Configuração RP da passkey divergente.");
  }
  const credential = await navigator.credentials.get({ publicKey });
  if (!credential || credential.type !== "public-key") {
    throw new Error("A autenticação com passkey foi cancelada.");
  }
  const response = await postJson(
    "/auth/passkeys/authentication/verify",
    {
      challengeId: started.challengeId,
      credential: assertionCredentialToJson(
        credential as PublicKeyCredential,
      ),
    },
    freshAuthenticationFetch,
  );
  const profile = parseProfile(response);
  clearSessionForCurrentDocument();
  clearRemoteSessionIsolation();
  setSession(profile);
  return profile;
}

export async function renewOfflineAccess(
  metadata: OfflineVaultMetadata,
): Promise<"READY" | "PRF_UNAVAILABLE"> {
  const renewed = await renewOfflineVault(metadata, async () =>
    parseSignedGrant(await postJson("/auth/offline-grant", null)),
  );
  if (renewed === "PRF_UNAVAILABLE") {
    return renewed;
  }
  await saveOfflineVaultMetadata(renewed);
  return "READY";
}

async function requestOptions(
  path: string,
  body: unknown = null,
  transport: PostTransport = apiFetch,
): Promise<WebAuthnOptionsResponse> {
  const response = await postJson(path, body, transport);
  const source = record(response, "Opções da passkey inválidas.");
  const challengeId = requiredString(source.challengeId);
  const rpId = requiredString(source.rpId);
  if (
    challengeId.length > 64 ||
    rpId.length > 253 ||
    !source.publicKey
  ) {
    throw new Error("Opções da passkey inválidas.");
  }
  return { challengeId, rpId, publicKey: source.publicKey };
}

async function postJson(
  path: string,
  body: unknown,
  transport: PostTransport = apiFetch,
): Promise<unknown> {
  const response = await transport(path, {
    method: "POST",
    ...(body === null
      ? {}
      : {
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        }),
  });
  const parsed = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(parsed, response.status));
  }
  return parsed;
}

function parsePasskeySummary(value: unknown): PasskeySummary {
  const source = record(value, "Resposta de registro da passkey inválida.");
  const credentialId = requiredString(source.credentialId);
  const name = requiredString(source.name);
  const createdAt = requiredString(source.createdAt);
  if (
    credentialId.length > 1_400 ||
    name.length > 120 ||
    !Number.isFinite(Date.parse(createdAt)) ||
    typeof source.prfSupported !== "boolean"
  ) {
    throw new Error("Resposta de registro da passkey inválida.");
  }
  return {
    credentialId,
    name,
    createdAt,
    prfSupported: source.prfSupported,
  };
}

function parseSignedGrant(value: unknown): SignedOfflineGrant {
  const source = record(value, "Grant offline inválido.");
  const keys = Object.keys(source).sort();
  if (
    keys.join(",") !== "keyId,payload,publicKeySpki,signature" ||
    !boundedString(source.keyId, 1, 80) ||
    !boundedString(source.payload, 1, 50_000) ||
    !boundedString(source.signature, 1, 12_000) ||
    !boundedString(source.publicKeySpki, 1, 12_000)
  ) {
    throw new Error("Grant offline inválido.");
  }
  return {
    keyId: source.keyId,
    payload: source.payload,
    signature: source.signature,
    publicKeySpki: source.publicKeySpki,
  };
}

function parseProfile(value: unknown): AuthProfile {
  const source = record(value, "Perfil de autenticação inválido.");
  const colaboradorId = requiredString(source.colaboradorId);
  const nome = requiredString(source.nome);
  const expiraEm = requiredString(source.expiraEm);
  const obraIds = Array.isArray(source.obraIds)
    ? source.obraIds.map(requiredString)
    : null;
  if (
    colaboradorId.length > 64 ||
    nome.length > 255 ||
    (source.papelAcesso !== "ALFA" && source.papelAcesso !== "BETA") ||
    typeof source.escopoGlobal !== "boolean" ||
    source.escopoGlobal !== (source.papelAcesso === "ALFA") ||
    obraIds === null ||
    obraIds.length > LIMITE_DE_OBRAS_NO_ESCOPO ||
    (source.escopoGlobal && obraIds.length > 0) ||
    !Number.isFinite(Date.parse(expiraEm))
  ) {
    throw new Error("Perfil de autenticação inválido.");
  }
  return {
    colaboradorId,
    nome,
    papelAcesso: source.papelAcesso,
    escopoGlobal: source.escopoGlobal,
    obraIds: [...new Set(obraIds)].sort(),
    expiraEm,
  };
}

function record(value: unknown, message: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(message);
  }
  return value as Record<string, unknown>;
}

function requiredString(value: unknown): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new Error("Resposta de passkey inválida.");
  }
  return value.trim();
}

function boundedString(
  value: unknown,
  minimum: number,
  maximum: number,
): value is string {
  return typeof value === "string" &&
    value.length >= minimum &&
    value.length <= maximum;
}
