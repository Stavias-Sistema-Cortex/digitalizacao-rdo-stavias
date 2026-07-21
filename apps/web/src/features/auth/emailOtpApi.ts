import { apiError } from "../../lib/api/apiError";
import { parseAuthProfile } from "./authProfile";
import type { AuthProfile } from "./authSession";
import {
  publicAuthFetch,
  readPublicAuthResponse,
} from "./emailOtpTransport";

const CHALLENGE_ID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SIX_DIGIT_CODE = /^\d{6}$/;

export type EmailOtpChallenge = {
  challengeId: string;
};

export async function requestEmailOtpChallenge(
  identifier: string,
): Promise<EmailOtpChallenge> {
  const email = identifier.trim();
  if (!email || email.length > 320) {
    throw new Error("Informe um e-mail institucional válido.");
  }

  const response = await publicAuthFetch("/auth/email/challenges", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ identifier: email }),
  });
  const body = await readPublicAuthResponse(response);
  if (!response.ok) {
    throw apiError(body, response.status);
  }

  return parseChallenge(body);
}

export async function verifyEmailOtpChallenge(
  challengeId: string,
  code: string,
): Promise<AuthProfile> {
  if (!CHALLENGE_ID.test(challengeId) || !SIX_DIGIT_CODE.test(code)) {
    throw new Error("Código de autenticação inválido.");
  }

  const response = await publicAuthFetch(
    `/auth/email/challenges/${challengeId}/verify`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
    },
  );
  const body = await readPublicAuthResponse(response);
  if (!response.ok) {
    throw apiError(body, response.status);
  }
  return parseAuthProfile(body);
}

function parseChallenge(body: unknown): EmailOtpChallenge {
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    throw new Error("Desafio de autenticação inválido.");
  }
  const values = body as Record<string, unknown>;
  const challengeId = values.challengeId;
  const expiresInSeconds = values.expiresInSeconds;
  if (
    typeof challengeId !== "string" ||
    !CHALLENGE_ID.test(challengeId) ||
    typeof expiresInSeconds !== "number" ||
    !Number.isInteger(expiresInSeconds) ||
    expiresInSeconds < 1 ||
    expiresInSeconds > 86_400
  ) {
    throw new Error("Desafio de autenticação inválido.");
  }
  return { challengeId };
}
