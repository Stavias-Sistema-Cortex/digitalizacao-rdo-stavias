import {
  clearSession,
  getSession,
} from "../../features/auth/authSession";

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

const AUTH_PATHS = ["/auth/login"];

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
      "Não foi possível conectar ao Córtex local. Verifique se a API está ligada e se esta origem está liberada no CORS; os dados seguem salvos neste dispositivo.",
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

export async function apiFetch(
  path: string,
  options: ApiRequestOptions = {},
): Promise<Response> {
  const session = getSession();
  const authHeader = session?.token ? `Bearer ${session.token}` : undefined;

  const response = await rawFetch(path, options, authHeader);

  // Um 401 prova que o token não é mais aceito. Em vez de reter o CPF como
  // senha para uma renovação silenciosa, encerra a sessão e pede autenticação
  // explícita quando houver conexão.
  if (response.status === 401 && !isAuthPath(path)) {
    clearSession();
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
  const message =
    responseField(body, "message") ??
    responseField(body, "detail") ??
    responseField(body, "error") ??
    (typeof body === "string"
      ? body
      : `HTTP ${fallbackStatus}`);

  return normalizeResponseErrorMessage(message);
}

function normalizeResponseErrorMessage(message: string): string {
  if (/invalid cors request/i.test(message)) {
    return "A API recusou a origem desta tela (CORS). Abra o Córtex por localhost/127.0.0.1 ou configure CORTEX_CORS_ALLOWED_ORIGIN_PATTERNS no backend.";
  }

  return message;
}
