import { requireDataScope } from "../../features/auth/authSession";

const OWNER_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export async function currentDataDatabaseName(): Promise<string> {
  const scope = requireDataScope();
  return databaseNameForScope(scope.ownerId, scope.scopeMaterial);
}

export async function databaseNameForScope(
  ownerId: string,
  scopeMaterial: string,
): Promise<string> {
  if (!OWNER_PATTERN.test(ownerId) || !scopeMaterial.trim()) {
    throw new Error("Escopo local inválido.");
  }
  const fingerprint = await scopeFingerprint(ownerId, scopeMaterial);
  return `cortex-data-v1-${ownerId}-${fingerprint}`;
}

export async function scopeFingerprint(
  ownerId: string,
  scopeMaterial: string,
): Promise<string> {
  const digest = new Uint8Array(
    await crypto.subtle.digest(
      "SHA-256",
      new TextEncoder().encode(`${ownerId}\n${scopeMaterial}`),
    ),
  );
  return Array.from(digest, (value) => value.toString(16).padStart(2, "0"))
    .join("");
}
