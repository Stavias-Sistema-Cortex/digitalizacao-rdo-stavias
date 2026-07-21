import { clearSession } from "../../features/auth/authSession";
import { apiUrl } from "./apiEndpoint";
import {
  ApiError,
  apiError,
  responseErrorMessage,
  responseField,
} from "./apiError";

const CSRF_COOKIE = "cortex_csrf";
const CSRF_HEADER = "X-CSRF-Token";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS", "TRACE"]);

export {
  ApiError,
  apiError,
  apiUrl,
  responseErrorMessage,
  responseField,
};

export interface ApiRequestOptions extends RequestInit {
  timeoutMs?: number;
  connectionErrorMessage?: string;
  timeoutErrorMessage?: string;
}

async function rawFetch(
  path: string,
  options: ApiRequestOptions,
): Promise<Response> {
  const {
    timeoutMs = 20_000,
    headers: requestedHeaders,
    connectionErrorMessage =
      "Não foi possível conectar ao Córtex local. Verifique se a API está ligada e se esta origem está liberada no CORS; os dados seguem salvos neste dispositivo.",
    timeoutErrorMessage =
      "Tempo limite excedido ao conectar ao Córtex local.",
    ...fetchOptions
  } = options;

  const headers = new Headers(requestedHeaders);
  headers.set("Accept", "application/json");
  headers.delete("Authorization");
  headers.delete(CSRF_HEADER);
  const method = (fetchOptions.method ?? "GET").toUpperCase();
  if (!SAFE_METHODS.has(method)) {
    const csrf = csrfCookie();
    if (csrf) {
      headers.set(CSRF_HEADER, csrf);
    }
  }

  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(apiUrl(path), {
      ...fetchOptions,
      credentials: "include",
      signal: controller.signal,
      headers,
    });
  } catch (error: unknown) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new Error(timeoutErrorMessage, { cause: error });
    }
    if (error instanceof TypeError) {
      throw new Error(connectionErrorMessage, { cause: error });
    }
    throw error;
  } finally {
    window.clearTimeout(timeoutId);
  }
}

export async function apiFetch(
  path: string,
  options: ApiRequestOptions = {},
): Promise<Response> {
  const response = await rawFetch(path, options);
  if (
    response.status === 401 &&
    !isPublicAuthenticationPath(path)
  ) {
    clearSession();
  }
  return response;
}

function isPublicAuthenticationPath(path: string): boolean {
  const pathname = path.split(/[?#]/, 1)[0];
  return pathname === "/auth/email/challenges" ||
    pathname === "/auth/passkeys/authentication/options" ||
    pathname === "/auth/passkeys/authentication/verify" ||
    /^\/auth\/email\/challenges\/[^/]{1,64}\/verify$/.test(
      pathname,
    );
}

function csrfCookie(): string | null {
  if (typeof document === "undefined") {
    return null;
  }
  const matches = document.cookie
    .split(";")
    .map((entry) => entry.trim())
    .filter((entry) => entry.startsWith(`${CSRF_COOKIE}=`));
  if (matches.length !== 1) {
    return null;
  }
  try {
    const value = decodeURIComponent(
      matches[0].slice(CSRF_COOKIE.length + 1),
    );
    return /^[A-Za-z0-9_-]{43}$/.test(value) ? value : null;
  } catch {
    return null;
  }
}

export async function readResponseBody(
  response: Response,
): Promise<unknown> {
  const responseText = await response.text();
  if (!responseText.trim()) {
    return null;
  }
  try {
    return JSON.parse(responseText);
  } catch {
    return responseText;
  }
}
