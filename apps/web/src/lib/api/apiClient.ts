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

export async function apiFetch(
  path: string,
  options: ApiRequestOptions = {},
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
