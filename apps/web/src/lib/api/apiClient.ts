import { clearSession } from "../../features/auth/authSession";

const DEFAULT_API_PREFIX = "/api";
const CSRF_COOKIE = "cortex_csrf";
const CSRF_HEADER = "X-CSRF-Token";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS", "TRACE"]);

export interface ApiRequestOptions extends RequestInit {
  timeoutMs?: number;
  connectionErrorMessage?: string;
  timeoutErrorMessage?: string;
}

export function apiUrl(path: string): string {
  const configuredBaseUrl =
    import.meta.env.VITE_CORTEX_API_BASE_URL?.trim();
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  if (!configuredBaseUrl) {
    return `${DEFAULT_API_PREFIX}${normalizedPath}`;
  }
  return `${validatedApiBaseUrl(configuredBaseUrl)}${normalizedPath}`;
}

function validatedApiBaseUrl(configuredBaseUrl: string): string {
  const isRootRelative =
    configuredBaseUrl.startsWith("/") &&
    !configuredBaseUrl.startsWith("//");
  const isAbsoluteHttp = /^https?:\/\//i.test(configuredBaseUrl);
  if (!isRootRelative && !isAbsoluteHttp) {
    throw new Error(
      "VITE_CORTEX_API_BASE_URL deve ser uma URL absoluta http(s) ou iniciar com /.",
    );
  }

  const browserOrigin =
    typeof window !== "undefined"
      ? window.location.origin
      : "http://localhost";
  let parsed: URL;
  try {
    parsed = new URL(configuredBaseUrl, browserOrigin);
  } catch {
    throw new Error(
      "VITE_CORTEX_API_BASE_URL não é uma URL válida.",
    );
  }

  if (
    parsed.username ||
    parsed.password ||
    parsed.search ||
    parsed.hash
  ) {
    throw new Error(
      "VITE_CORTEX_API_BASE_URL não pode conter credenciais, query ou fragmento.",
    );
  }

  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error(
      "VITE_CORTEX_API_BASE_URL deve usar HTTP ou HTTPS.",
    );
  }

  if (
    !isRootRelative &&
    typeof window !== "undefined" &&
    (parsed.hostname !== window.location.hostname ||
      parsed.protocol !== window.location.protocol)
  ) {
    throw new Error(
      "A API e a PWA devem usar o mesmo hostname e protocolo para que o cookie CSRF host-only funcione. Publique /api na mesma origem ou use apenas outra porta no desenvolvimento.",
    );
  }

  const normalizedPath =
    parsed.pathname === "/"
      ? ""
      : parsed.pathname.replace(/\/+$/, "");
  return isRootRelative
    ? normalizedPath
    : `${parsed.origin}${normalizedPath}`;
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

export function responseField(
  body: unknown,
  field: "message" | "detail" | "error",
): string | null {
  if (typeof body === "object" && body !== null && field in body) {
    const value = (body as Record<string, unknown>)[field];
    return typeof value === "string" ? value : null;
  }
  return null;
}

export function responseErrorMessage(
  body: unknown,
  fallbackStatus: number,
): string {
  const message =
    responseField(body, "message") ??
    responseField(body, "detail") ??
    responseField(body, "error") ??
    (typeof body === "string" ? body : `HTTP ${fallbackStatus}`);
  return normalizeResponseErrorMessage(message);
}

function normalizeResponseErrorMessage(message: string): string {
  if (/invalid cors request/i.test(message)) {
    return "A API recusou a origem desta tela (CORS). Abra o Córtex por uma origem autorizada ou configure CORTEX_CORS_ALLOWED_ORIGINS no backend.";
  }
  return message;
}
