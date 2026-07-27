import { ApiError, ApiTransportError } from "../api/apiError";

export interface AutomaticRequestFailureDisposition {
  retryable: boolean;
  safeCode: string;
  message: string;
}

export function classifyAutomaticRequestFailure(
  error: unknown,
): AutomaticRequestFailureDisposition {
  if (error instanceof ApiTransportError) {
    return {
      retryable: true,
      safeCode:
        error.kind === "TIMEOUT"
          ? "NETWORK_TIMEOUT"
          : "NETWORK_UNAVAILABLE",
      message: error.message,
    };
  }
  if (error instanceof ApiError) {
    const retryable =
      error.status === 408 ||
      error.status === 429 ||
      (error.status >= 500 && error.status <= 599);
    return {
      retryable,
      safeCode:
        error.code ??
        `HTTP_${error.status}_${retryable ? "TRANSIENT" : "TERMINAL"}`,
      message: error.message,
    };
  }
  return {
    retryable: false,
    safeCode: "LOCAL_REQUEST_INVALID",
    message:
      error instanceof Error
        ? error.message
        : "Falha local inválida durante a sincronização.",
  };
}
