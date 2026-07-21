import type { AuthProfile } from "./authSession";

/** Parses only the safe session projection returned by the cookie API. */
export function parseAuthProfile(body: unknown): AuthProfile {
  const data = record(body, "Perfil de autenticação inválido.");
  const colaboradorId = requiredString(data.colaboradorId);
  const nome = requiredString(data.nome);
  const papelAcesso = data.papelAcesso;
  const escopoGlobal = data.escopoGlobal;
  const obraIds = canonicalIds(data.obraIds);
  const expiraEm = requiredString(data.expiraEm);
  if (
    !colaboradorId ||
    colaboradorId.length > 64 ||
    !nome ||
    nome.length > 255 ||
    (papelAcesso !== "ALFA" && papelAcesso !== "BETA") ||
    typeof escopoGlobal !== "boolean" ||
    escopoGlobal !== (papelAcesso === "ALFA") ||
    obraIds === null ||
    (escopoGlobal && obraIds.length > 0) ||
    !expiraEm ||
    !Number.isFinite(Date.parse(expiraEm))
  ) {
    throw new Error("Perfil de autenticação inválido.");
  }
  return {
    colaboradorId,
    nome,
    papelAcesso,
    escopoGlobal,
    obraIds,
    expiraEm,
  };
}

function canonicalIds(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.length > 800) {
    return null;
  }
  const ids = value.map(requiredString);
  if (ids.some((id) => !id || id.length > 64)) {
    return null;
  }
  return [...new Set(ids as string[])].sort();
}

function record(
  value: unknown,
  message: string,
): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(message);
  }
  return value as Record<string, unknown>;
}

function requiredString(value: unknown): string | null {
  return typeof value === "string" && value.trim()
    ? value.trim()
    : null;
}
