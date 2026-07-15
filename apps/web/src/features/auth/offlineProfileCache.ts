import type { AuthSession } from "./authSession";

const PROFILE_PREFIX = "cortex.auth.offline-profile.";

type CachedOfflineProfile = Pick<
  AuthSession,
  "colaboradorId" | "nome" | "cpfMascarado" | "perfil" | "papelAcesso"
>;

async function cpfFingerprint(cpfDigits: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(cpfDigits),
  );
  return [...new Uint8Array(digest)]
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
}

export async function cacheOfflineProfile(
  cpfDigits: string,
  session: AuthSession,
): Promise<void> {
  if (!session.colaboradorId) {
    throw new Error("O perfil online não possui identidade local válida.");
  }
  const fingerprint = await cpfFingerprint(cpfDigits);
  const profile: CachedOfflineProfile = {
    colaboradorId: session.colaboradorId,
    nome: session.nome,
    cpfMascarado: session.cpfMascarado,
    perfil: session.perfil,
    papelAcesso: session.papelAcesso,
  };
  localStorage.setItem(`${PROFILE_PREFIX}${fingerprint}`, JSON.stringify(profile));
}

export async function getCachedOfflineProfile(
  cpfDigits: string,
): Promise<CachedOfflineProfile | null> {
  const fingerprint = await cpfFingerprint(cpfDigits);
  const raw = localStorage.getItem(`${PROFILE_PREFIX}${fingerprint}`);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as CachedOfflineProfile;
    return parsed?.colaboradorId && typeof parsed.cpfMascarado === "string"
      ? parsed
      : null;
  } catch {
    return null;
  }
}
