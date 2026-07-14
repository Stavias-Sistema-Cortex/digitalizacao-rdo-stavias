import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";
import type { AuthProfile } from "./authSession";

export type OtpChallenge = {
  challengeId: string;
  expiresInSeconds: number;
  message: string;
};

export async function requestEmailCode(
  identifier: string,
): Promise<OtpChallenge> {
  const response = await apiFetch("/auth/email/challenges", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ identifier }),
  });
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw responseError(body, response.status);
  }
  return parseChallenge(body);
}

export async function verifyEmailCode(
  challengeId: string,
  code: string,
): Promise<AuthProfile> {
  const response = await apiFetch(
    `/auth/email/challenges/${encodeURIComponent(challengeId)}/verify`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
    },
  );
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

function parseChallenge(body: unknown): OtpChallenge {
  const data = record(body, "Desafio de autenticação inválido.");
  const challengeId = requiredString(data.challengeId);
  const message = requiredString(data.message);
  const expiresInSeconds = data.expiresInSeconds;
  if (
    !challengeId ||
    challengeId.length > 64 ||
    !message ||
    message.length > 500 ||
    !Number.isInteger(expiresInSeconds) ||
    (expiresInSeconds as number) < 1 ||
    (expiresInSeconds as number) > 86_400
  ) {
    throw new Error("Desafio de autenticação inválido.");
  }
  return {
    challengeId,
    expiresInSeconds: expiresInSeconds as number,
    message,
  };
}

function parseProfile(body: unknown): AuthProfile {
  const data = record(body, "Perfil de autenticação inválido.");
  const colaboradorId = requiredString(data.colaboradorId);
  const nome = requiredString(data.nome);
  const papelAcesso = data.papelAcesso;
  const expiraEm = requiredString(data.expiraEm);
  if (
    !colaboradorId ||
    colaboradorId.length > 64 ||
    !nome ||
    nome.length > 200 ||
    (papelAcesso !== "ALFA" && papelAcesso !== "BETA") ||
    !expiraEm ||
    !Number.isFinite(Date.parse(expiraEm))
  ) {
    throw new Error("Perfil de autenticação inválido.");
  }
  return { colaboradorId, nome, papelAcesso, expiraEm };
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
