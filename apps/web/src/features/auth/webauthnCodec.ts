const BASE64URL_PATTERN = /^[A-Za-z0-9_-]+$/;

export function fromBase64Url(
  value: string,
  maximumBytes = 65_536,
): Uint8Array<ArrayBuffer> {
  if (
    !value ||
    !BASE64URL_PATTERN.test(value) ||
    value.length > Math.ceil((maximumBytes * 4) / 3) + 4
  ) {
    throw new Error("Valor WebAuthn base64url inválido.");
  }
  const padding = "=".repeat((4 - (value.length % 4)) % 4);
  let binary: string;
  try {
    binary = atob(value.replace(/-/g, "+").replace(/_/g, "/") + padding);
  } catch {
    throw new Error("Valor WebAuthn base64url inválido.");
  }
  if (binary.length < 1 || binary.length > maximumBytes) {
    throw new Error("Valor WebAuthn base64url inválido.");
  }
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

export function toBase64Url(
  value: ArrayBuffer | ArrayBufferView,
): string {
  const source = value instanceof ArrayBuffer
    ? new Uint8Array(value)
    : new Uint8Array(
        value.buffer,
        value.byteOffset,
        value.byteLength,
      );
  let binary = "";
  for (const byte of source) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

export function bytesEqual(
  left: Uint8Array,
  right: Uint8Array,
): boolean {
  if (left.byteLength !== right.byteLength) {
    return false;
  }
  let difference = 0;
  for (let index = 0; index < left.byteLength; index += 1) {
    difference |= left[index] ^ right[index];
  }
  return difference === 0;
}

export function decodeCreationOptions(
  value: unknown,
): PublicKeyCredentialCreationOptions {
  const source = record(value, "Opções de registro da passkey inválidas.");
  const rp = record(source.rp, "RP da passkey inválido.");
  const user = record(source.user, "Usuário da passkey inválido.");
  const parameters = source.pubKeyCredParams;
  if (
    !boundedString(rp.id, 1, 253) ||
    !boundedString(rp.name, 1, 120) ||
    !boundedString(user.name, 1, 200) ||
    !boundedString(user.displayName, 1, 200) ||
    !Array.isArray(parameters) ||
    parameters.length < 1 ||
    parameters.length > 32
  ) {
    throw new Error("Opções de registro da passkey inválidas.");
  }
  const options = {
    ...source,
    challenge: fromBase64Url(requiredString(source.challenge), 1_024),
    rp: { ...rp, id: rp.id, name: rp.name },
    user: {
      ...user,
      id: fromBase64Url(requiredString(user.id), 64),
      name: user.name,
      displayName: user.displayName,
    },
    pubKeyCredParams: parameters,
    excludeCredentials: decodeDescriptors(source.excludeCredentials),
    extensions: decodeCreationExtensions(source.extensions),
  };
  return options as unknown as PublicKeyCredentialCreationOptions;
}

export function decodeRequestOptions(
  value: unknown,
): PublicKeyCredentialRequestOptions {
  const source = record(value, "Opções de autenticação da passkey inválidas.");
  const rpId = requiredString(source.rpId);
  if (!rpId || rpId.length > 253) {
    throw new Error("Opções de autenticação da passkey inválidas.");
  }
  return {
    ...source,
    challenge: fromBase64Url(requiredString(source.challenge), 1_024),
    rpId,
    allowCredentials: decodeDescriptors(source.allowCredentials),
  } as unknown as PublicKeyCredentialRequestOptions;
}

export function registrationCredentialToJson(
  credential: PublicKeyCredential,
): Record<string, unknown> {
  const response = credential.response as AuthenticatorAttestationResponse;
  assertCredentialIdentity(credential);
  if (!response?.clientDataJSON || !response.attestationObject) {
    throw new Error("Resposta de registro da passkey inválida.");
  }
  const extensions = credential.getClientExtensionResults();
  return {
    id: credential.id,
    rawId: toBase64Url(credential.rawId),
    type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment ?? null,
    response: {
      clientDataJSON: toBase64Url(response.clientDataJSON),
      attestationObject: toBase64Url(response.attestationObject),
      transports: typeof response.getTransports === "function"
        ? response.getTransports()
        : [],
    },
    clientExtensionResults: sanitizedRegistrationExtensions(extensions),
  };
}

export function assertionCredentialToJson(
  credential: PublicKeyCredential,
): Record<string, unknown> {
  const response = credential.response as AuthenticatorAssertionResponse;
  assertCredentialIdentity(credential);
  if (
    !response?.clientDataJSON ||
    !response.authenticatorData ||
    !response.signature
  ) {
    throw new Error("Resposta de autenticação da passkey inválida.");
  }
  return {
    id: credential.id,
    rawId: toBase64Url(credential.rawId),
    type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment ?? null,
    response: {
      clientDataJSON: toBase64Url(response.clientDataJSON),
      authenticatorData: toBase64Url(response.authenticatorData),
      signature: toBase64Url(response.signature),
      userHandle: response.userHandle
        ? toBase64Url(response.userHandle)
        : null,
    },
    clientExtensionResults: {},
  };
}

export function extractPrfResult(
  credential: PublicKeyCredential,
): Uint8Array<ArrayBuffer> | null {
  const outputs = credential.getClientExtensionResults() as unknown;
  if (!isRecord(outputs) || !isRecord(outputs.prf)) {
    return null;
  }
  const results = outputs.prf.results;
  if (!isRecord(results)) {
    return null;
  }
  const first = results.first;
  if (first instanceof ArrayBuffer) {
    const copy = new Uint8Array(first.slice(0));
    return copy.byteLength === 32 ? copy : null;
  }
  if (ArrayBuffer.isView(first)) {
    const source = new Uint8Array(
      first.buffer,
      first.byteOffset,
      first.byteLength,
    );
    const copy = new Uint8Array(source);
    return copy.byteLength === 32 ? copy : null;
  }
  return null;
}

export function creationPrfSalt(
  options: PublicKeyCredentialCreationOptions,
): Uint8Array<ArrayBuffer> | null {
  const extensions = options.extensions as unknown;
  if (!isRecord(extensions) || !isRecord(extensions.prf)) {
    return null;
  }
  const evaluation = extensions.prf.eval;
  if (!isRecord(evaluation)) {
    return null;
  }
  const first = evaluation.first;
  if (first instanceof ArrayBuffer) {
    const copy = new Uint8Array(first.slice(0));
    return copy.byteLength === 32 ? copy : null;
  }
  if (ArrayBuffer.isView(first)) {
    const copy = new Uint8Array(
      new Uint8Array(first.buffer, first.byteOffset, first.byteLength),
    );
    return copy.byteLength === 32 ? copy : null;
  }
  return null;
}

function decodeDescriptors(value: unknown): PublicKeyCredentialDescriptor[] | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }
  if (!Array.isArray(value) || value.length > 100) {
    throw new Error("Lista de credenciais WebAuthn inválida.");
  }
  return value.map((item) => {
    const descriptor = record(item, "Credencial WebAuthn inválida.");
    if (descriptor.type !== "public-key") {
      throw new Error("Credencial WebAuthn inválida.");
    }
    return {
      ...descriptor,
      id: fromBase64Url(requiredString(descriptor.id), 1_024),
      type: "public-key" as const,
    } as PublicKeyCredentialDescriptor;
  });
}

function decodeCreationExtensions(
  value: unknown,
): AuthenticationExtensionsClientInputs | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }
  const extensions = record(value, "Extensões WebAuthn inválidas.");
  if (!isRecord(extensions.prf)) {
    return extensions as AuthenticationExtensionsClientInputs;
  }
  const evaluation = extensions.prf.eval;
  if (!isRecord(evaluation)) {
    throw new Error("Extensão PRF inválida.");
  }
  return {
    ...extensions,
    prf: {
      ...extensions.prf,
      eval: {
        ...evaluation,
        first: fromBase64Url(requiredString(evaluation.first), 32),
      },
    },
  } as AuthenticationExtensionsClientInputs;
}

function sanitizedRegistrationExtensions(
  value: AuthenticationExtensionsClientOutputs,
): Record<string, unknown> {
  const source = value as unknown;
  if (!isRecord(source)) {
    return {};
  }
  const sanitized: Record<string, unknown> = {};
  if (isRecord(source.credProps) && typeof source.credProps.rk === "boolean") {
    sanitized.credProps = { rk: source.credProps.rk };
  }
  if (isRecord(source.prf) && typeof source.prf.enabled === "boolean") {
    // O resultado PRF é segredo local e não precisa chegar ao servidor.
    sanitized.prf = { enabled: source.prf.enabled };
  }
  return sanitized;
}

function assertCredentialIdentity(credential: PublicKeyCredential): void {
  if (
    credential.type !== "public-key" ||
    !credential.id ||
    !credential.rawId ||
    credential.id !== toBase64Url(credential.rawId)
  ) {
    throw new Error("Credencial WebAuthn inválida.");
  }
}

function record(value: unknown, message: string): Record<string, unknown> {
  if (!isRecord(value)) {
    throw new Error(message);
  }
  return value;
}

function requiredString(value: unknown): string {
  if (typeof value !== "string" || !value) {
    throw new Error("Valor WebAuthn obrigatório ausente.");
  }
  return value;
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
