const API_ERROR_CODE = /^[A-Z][A-Z0-9_]{2,63}$/;
const GENERIC_API_ERROR_MESSAGE =
  "Não foi possível concluir a solicitação ao Córtex.";
const ACTIVATION_ONLY_MESSAGE =
  "Ativação inicial do Córtex em andamento.";

/**
 * A narrow error shape for decisions made by the browser. The response body is
 * intentionally not retained: callers get a safe message, status and a
 * bounded machine code only.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string | null;

  constructor(message: string, status: number, code: string | null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

export type ApiTransportFailureKind =
  | "CONNECTION"
  | "TIMEOUT"
  | "REMOTE_SESSION_ISOLATED"
  | "CLIENT_INSTANCE_REAUTH_REQUIRED"
  | "CLIENT_INSTANCE_UNAVAILABLE";

/** A typed transport failure so retry decisions never inspect display text. */
export class ApiTransportError extends Error {
  readonly kind: ApiTransportFailureKind;

  constructor(
    message: string,
    kind: ApiTransportFailureKind,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = "ApiTransportError";
    this.kind = kind;
  }
}

export function responseErrorCode(body: unknown): string | null {
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    return null;
  }
  const code = (body as Record<string, unknown>).code;
  return typeof code === "string" && API_ERROR_CODE.test(code)
    ? code
    : null;
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
  if (/invalid cors request/i.test(message)) {
    return "A API recusou a origem desta tela (CORS). Abra o Córtex por uma origem autorizada ou configure CORTEX_CORS_ALLOWED_ORIGINS no backend.";
  }
  return message;
}

/** Returns only the response status, bounded machine code and safe message. */
export function apiError(body: unknown, status: number): ApiError {
  const code = responseErrorCode(body);
  return new ApiError(
    code === "CORTEX_ACTIVATION_ONLY"
      ? ACTIVATION_ONLY_MESSAGE
      : GENERIC_API_ERROR_MESSAGE,
    status,
    code,
  );
}
