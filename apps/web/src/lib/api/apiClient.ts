import {
  clearSessionForCurrentDocument,
  hasOfflineSession,
} from "../../features/auth/authSession";
import { hasRemoteSessionIsolation } from "../../features/auth/remoteSessionIsolation";
import { apiUrl } from "./apiEndpoint";
import {
  CLIENT_INSTANCE_HEADER,
  getOrCreateClientInstance,
  markClientInstanceAuthenticated,
} from "./clientInstance";
import {
  ApiError,
  ApiTransportError,
  apiError,
  responseErrorMessage,
  responseField,
} from "./apiError";

const CSRF_COOKIE = "cortex_csrf";
const CSRF_HEADER = "X-CSRF-Token";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS", "TRACE"]);
const FRESH_AUTHENTICATION_PATHS = new Set([
  "/auth/login",
  "/auth/passkeys/authentication/options",
  "/auth/passkeys/authentication/verify",
]);

export {
  ApiError,
  ApiTransportError,
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
  allowFreshAuthentication = false,
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
  headers.delete(CLIENT_INSTANCE_HEADER);
  if (requiresClientInstance(path, method)) {
    const lease = await getOrCreateClientInstance();
    if (lease === null) {
      throw new ApiTransportError(
        "Não foi possível preparar a prova desta aba. Atualize a página e entre novamente.",
        "CLIENT_INSTANCE_UNAVAILABLE",
      );
    }
    if (lease.requiresFreshAuthentication && !allowFreshAuthentication) {
      throw new ApiTransportError(
        "Esta aba precisa de uma nova autenticação antes de acessar a sessão compartilhada.",
        "CLIENT_INSTANCE_REAUTH_REQUIRED",
      );
    }
    headers.set(CLIENT_INSTANCE_HEADER, lease.value);
  }
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
      throw new ApiTransportError(
        timeoutErrorMessage,
        "TIMEOUT",
        { cause: error },
      );
    }
    if (error instanceof TypeError) {
      throw new ApiTransportError(
        connectionErrorMessage,
        "CONNECTION",
        { cause: error },
      );
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
  if (hasOfflineSession() || hasRemoteSessionIsolation()) {
    throw new ApiTransportError(
      "O acesso remoto está isolado até uma nova autenticação.",
      "REMOTE_SESSION_ISOLATED",
    );
  }
  const response = await rawFetch(path, options);
  if (
    response.status === 401 &&
    !isPublicAuthenticationPath(path)
  ) {
    clearSessionForCurrentDocument();
  }
  return response;
}

/**
 * Deliberately narrow escape hatch for a person explicitly starting a new
 * credentialed sign-in. The path and method are checked here rather than
 * allowing callers to opt out of the normal isolation gate.
 */
export async function freshAuthenticationFetch(
  path: string,
  options: ApiRequestOptions,
): Promise<Response> {
  if (
    (options.method ?? "GET").toUpperCase() !== "POST" ||
    !FRESH_AUTHENTICATION_PATHS.has(path)
  ) {
    throw new Error("Transição de autenticação nova inválida.");
  }
  const response = await rawFetch(path, options, true);
  if (response.ok && establishesSession(path)) {
    const lease = await getOrCreateClientInstance();
    if (lease !== null) {
      markClientInstanceAuthenticated(lease.value);
    }
  }
  return response;
}

/**
 * The only protected follow-up permitted while a CPF fresh-login transition
 * remains isolated. Its signed owner is verified before normal API access is
 * released by the caller.
 */
export async function fetchFreshCpfOfflineGrant(): Promise<Response> {
  return rawFetch("/auth/offline-grant", { method: "POST" });
}

/** Exact server-side cookie revocation; not available through ordinary API fetch. */
export async function revokeRemoteSessionCookie(): Promise<Response> {
  return rawFetch("/auth/logout", { method: "POST" });
}

/**
 * Pergunta ao servidor de quem é o cookie, sem passar pelo isolamento.
 *
 * <p>O isolamento existe porque, depois de os dados serem abertos pelo modo
 * offline, um cookie HttpOnly deixa de provar que pertence a quem está neste
 * aparelho — pode ter sobrado de outra pessoa. Esta é a única pergunta que
 * responde exatamente isso, e ela não gasta a sessão: só lê a identidade que
 * o servidor associa ao cookie, para quem chama comparar com o dono do grant
 * que foi aberto aqui.
 *
 * <p>Nada é liberado por esta função. Ela devolve a resposta crua; soltar o
 * isolamento continua sendo decisão de quem faz a comparação.
 */
export async function fetchIsolatedSessionOwner(): Promise<Response> {
  return rawFetch("/auth/session", { method: "GET" });
}

function isPublicAuthenticationPath(path: string): boolean {
  const pathname = path.split(/[?#]/, 1)[0];
  return pathname === "/auth/email/challenges" ||
    pathname === "/auth/login" ||
    pathname === "/auth/passkeys/authentication/options" ||
    pathname === "/auth/passkeys/authentication/verify" ||
    /^\/auth\/email\/challenges\/[^/]{1,64}\/verify$/.test(
      pathname,
    );
}

function requiresClientInstance(path: string, method: string): boolean {
  if (method === "OPTIONS") {
    return false;
  }
  const pathname = path.split(/[?#]/, 1)[0];
  return pathname !== "/health" && pathname !== "/readiness";
}

function establishesSession(path: string): boolean {
  const pathname = path.split(/[?#]/, 1)[0];
  return pathname === "/auth/login" ||
    pathname === "/auth/passkeys/authentication/verify";
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
