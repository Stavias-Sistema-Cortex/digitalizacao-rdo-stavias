import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";
import type { AuthProfile } from "./authSession";

export async function loginWithCpf(cpf: string): Promise<AuthProfile> {
  const response = await apiFetch("/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ cpf }),
  });
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw responseError(body, response.status);
  }
  return parseProfile(body);
}

export async function fetchSession(): Promise<AuthProfile | null> {
  const response = await apiFetch("/auth/session");
  const body = await readResponseBody(response);
  if (response.status === 401) {
    return null;
  }
  if (!response.ok) {
    throw responseError(body, response.status);
  }
  return parseProfile(body);
}

export async function logoutOnline(): Promise<
  "revoked" | "already-expired"
> {
  const response = await apiFetch("/auth/logout", { method: "POST" });
  const body = await readResponseBody(response);
  if (response.status === 204) {
    return "revoked";
  }
  if (response.status === 401) {
    return "already-expired";
  }
  throw responseError(body, response.status);
}

function parseProfile(body: unknown): AuthProfile {
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

function responseError(body: unknown, status: number): Error {
  return new Error(responseErrorMessage(body, status));
}
