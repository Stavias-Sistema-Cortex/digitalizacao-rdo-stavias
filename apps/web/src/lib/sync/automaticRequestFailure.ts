import { ApiError, ApiTransportError } from "../api/apiError";

export interface AutomaticRequestFailureDisposition {
  retryable: boolean;
  safeCode: string;
  message: string;
  /**
   * O servidor julgou o que recebeu, ou nem chegou a olhar?
   *
   * <p>A diferença decide se uma mutação pode morrer. Quando a API recusa com
   * um código nomeado — acesso negado, envelope inválido — houve veredito, e
   * insistir não muda nada. Quando o status vem sem código, quem respondeu foi
   * o caminho: o filtro de CSRF diante de um cookie duplicado, um proxy que
   * comeu o cabeçalho, uma rota que sumiu no meio de um deploy. Aí nenhuma das
   * mutações do lote foi sequer lida, e tratá-las como recusadas apaga o dia de
   * trabalho de um aparelho inteiro por um problema de infraestrutura.
   */
  veredito: boolean;
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
      veredito: false,
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
      veredito: typeof error.code === "string" && error.code.length > 0,
    };
  }
  return {
    retryable: false,
    safeCode: "LOCAL_REQUEST_INVALID",
    message:
      error instanceof Error
        ? error.message
        : "Falha local inválida durante a sincronização.",
    veredito: false,
  };
}
