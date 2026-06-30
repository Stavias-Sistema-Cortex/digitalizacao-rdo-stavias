import { getSession, setSession } from "../../features/auth/authSession";

const DEFAULT_API_PREFIX = "/api";

export interface ApiRequestOptions extends RequestInit {
  timeoutMs?: number;
  connectionErrorMessage?: string;
  timeoutErrorMessage?: string;
}

export function apiUrl(path: string): string {
  const configuredBaseUrl =
    import.meta.env.VITE_CORTEX_API_BASE_URL?.trim();

  const normalizedPath = path.startsWith("/")
    ? path
    : `/${path}`;

  if (!configuredBaseUrl) {
    return `${DEFAULT_API_PREFIX}${normalizedPath}`;
  }

  return `${configuredBaseUrl.replace(/\/+$/, "")}${normalizedPath}`;
}

const AUTH_PATHS = ["/auth/login", "/auth/cpf-filter"];

function isAuthPath(path: string): boolean {
  return AUTH_PATHS.some(
    (authPath) => path === authPath || path.startsWith(`${authPath}/`),
  );
}

async function rawFetch(
  path: string,
  options: ApiRequestOptions,
  authHeader: string | undefined,
): Promise<Response> {
  const {
    timeoutMs = 20_000,
    headers,
    connectionErrorMessage =
      "Córtex local temporariamente indisponível. Os dados seguem salvos neste dispositivo e a sincronização será tentada novamente.",
    timeoutErrorMessage =
      "Tempo limite excedido ao conectar ao Córtex local.",
    ...fetchOptions
  } = options;

  const controller = new AbortController();
  const timeoutId = window.setTimeout(
    () => controller.abort(),
    timeoutMs,
  );

  try {
    return await fetch(apiUrl(path), {
      ...fetchOptions,
      signal: controller.signal,
      headers: {
        Accept: "application/json",
        ...(authHeader ? { Authorization: authHeader } : {}),
        ...headers,
      },
    });
  } catch (error: unknown) {
    if (
      error instanceof DOMException &&
      error.name === "AbortError"
    ) {
      throw new Error(timeoutErrorMessage, {
        cause: error,
      });
    }

    if (error instanceof TypeError) {
      throw new Error(connectionErrorMessage, {
        cause: error,
      });
    }

    throw error;
  } finally {
    window.clearTimeout(timeoutId);
  }
}

/**
 * Reautentica silenciosamente usando o CPF da sessão para renovar o token
 * (ex.: sessão offline que voltou a ter conexão, ou token expirado).
 */
async function reautenticarComCpf(cpfDigits: string): Promise<string | null> {
  try {
    const response = await fetch(apiUrl("/auth/login"), {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: JSON.stringify({ cpf: cpfDigits, senha: cpfDigits }),
    });

    if (!response.ok) {
      return null;
    }

    const body = (await response.json()) as { token?: unknown };
    const token = typeof body.token === "string" ? body.token : null;
    if (!token) {
      return null;
    }

    const session = getSession();
    if (session) {
      setSession({ ...session, token });
    }
    return token;
  } catch {
    return null;
  }
}

export async function apiFetch(
  path: string,
  options: ApiRequestOptions = {},
): Promise<Response> {
  const session = getSession();
  const authHeader = session?.token ? `Bearer ${session.token}` : undefined;

  let response = await rawFetch(path, options, authHeader);

  // Token ausente/expirado: tenta uma renovação silenciosa e repete a chamada.
  const online =
    typeof navigator === "undefined" || navigator.onLine;
  if (
    response.status === 401 &&
    !isAuthPath(path) &&
    session?.cpf &&
    online
  ) {
    const novoToken = await reautenticarComCpf(session.cpf);
    if (novoToken) {
      response = await rawFetch(path, options, `Bearer ${novoToken}`);
    }
  }

  return response;
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
  if (
    typeof body === "object" &&
    body !== null &&
    field in body
  ) {
    const value = (body as Record<string, unknown>)[
      field
    ];

    return typeof value === "string" ? value : null;
  }

  return null;
}

export function responseErrorMessage(
  body: unknown,
  fallbackStatus: number,
): string {
  return (
    responseField(body, "message") ??
    responseField(body, "detail") ??
    responseField(body, "error") ??
    (typeof body === "string"
      ? body
      : `HTTP ${fallbackStatus}`)
  );
}
