import { apiUrl } from "../../lib/api/apiEndpoint";
import {
  CLIENT_INSTANCE_HEADER,
  getOrCreateClientInstance,
  markClientInstanceAuthenticated,
} from "../../lib/api/clientInstance";

const REQUEST_TIMEOUT_MS = 20_000;
const EMAIL_CHALLENGE_PATH = "/auth/email/challenges";
const EMAIL_CHALLENGE_VERIFY_PATH =
  /^\/auth\/email\/challenges\/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\/verify$/;

/**
 * Cookie-only transport for the two exact public e-mail OTP transitions. It
 * has no dependency on the operational session module, offline vault, local
 * DB or automatic-sync graph, which keeps the activation root isolated.
 */
export async function publicAuthFetch(
  path: string,
  options: RequestInit,
): Promise<Response> {
  if (!isEmailOtpTransition(path, options.method)) {
    throw new Error("Transição pública de autenticação inválida.");
  }
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  headers.delete("Authorization");
  headers.delete("X-CSRF-Token");
  headers.delete(CLIENT_INSTANCE_HEADER);
  const lease = await getOrCreateClientInstance();
  if (lease === null) {
    throw new Error("Não foi possível preparar a prova desta aba.");
  }
  headers.set(CLIENT_INSTANCE_HEADER, lease.value);

  const controller = new AbortController();
  const timeoutId = window.setTimeout(
    () => controller.abort(),
    REQUEST_TIMEOUT_MS,
  );
  try {
    const response = await fetch(apiUrl(path), {
      ...options,
      credentials: "include",
      signal: controller.signal,
      headers,
    });
    if (response.ok && EMAIL_CHALLENGE_VERIFY_PATH.test(path)) {
      markClientInstanceAuthenticated(lease.value);
    }
    return response;
  } finally {
    window.clearTimeout(timeoutId);
  }
}

function isEmailOtpTransition(
  path: string,
  method: string | undefined,
): boolean {
  return method?.toUpperCase() === "POST" &&
    (path === EMAIL_CHALLENGE_PATH || EMAIL_CHALLENGE_VERIFY_PATH.test(path));
}

export async function readPublicAuthResponse(
  response: Response,
): Promise<unknown> {
  const responseText = await response.text();
  if (!responseText.trim()) {
    return null;
  }
  try {
    return JSON.parse(responseText);
  } catch {
    return null;
  }
}
