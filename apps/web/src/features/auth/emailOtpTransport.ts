import { apiUrl } from "../../lib/api/apiEndpoint";

const REQUEST_TIMEOUT_MS = 20_000;

/**
 * Cookie-only transport for the two public e-mail OTP endpoints. It has no
 * dependency on the operational session module, offline vault, local DB or
 * automatic-sync graph, which keeps the activation root isolated.
 */
export async function publicAuthFetch(
  path: string,
  options: RequestInit,
): Promise<Response> {
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  headers.delete("Authorization");
  headers.delete("X-CSRF-Token");

  const controller = new AbortController();
  const timeoutId = window.setTimeout(
    () => controller.abort(),
    REQUEST_TIMEOUT_MS,
  );
  try {
    return await fetch(apiUrl(path), {
      ...options,
      credentials: "include",
      signal: controller.signal,
      headers,
    });
  } finally {
    window.clearTimeout(timeoutId);
  }
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
