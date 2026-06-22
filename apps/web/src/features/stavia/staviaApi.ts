import type {
  StaviaConsultaRequest,
  StaviaConsultaResponse,
} from "./stavia.types";

const API_PREFIX = "/api";

export async function consultarStavia(
  request: StaviaConsultaRequest,
): Promise<StaviaConsultaResponse> {
  const controller = new AbortController();

  const timeoutId = window.setTimeout(
    () => controller.abort(),
    30_000,
  );

  try {
    const response = await fetch(
      `${API_PREFIX}/stavia/consultas`,
      {
        method: "POST",
        signal: controller.signal,
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
        },
        body: JSON.stringify(request),
      },
    );

    const responseText = await response.text();

    let body: unknown = null;

    if (responseText.trim()) {
      try {
        body = JSON.parse(responseText);
      } catch {
        body = responseText;
      }
    }

    if (!response.ok) {
      const serverMessage =
        typeof body === "object" &&
        body !== null &&
        "message" in body &&
        typeof body.message === "string"
          ? body.message
          : typeof body === "string"
            ? body
            : `HTTP ${response.status}`;

      throw new Error(
        `Falha na consulta ${response.status}: ${serverMessage}`,
      );
    }

    if (
      typeof body !== "object" ||
      body === null ||
      !("answer" in body) ||
      !("intent" in body)
    ) {
      throw new Error(
        "O backend retornou uma resposta inválida para a Stav.IA.",
      );
    }

    return body as StaviaConsultaResponse;
  } catch (error: unknown) {
    if (
      error instanceof DOMException &&
      error.name === "AbortError"
    ) {
      throw new Error(
        "A consulta à Stav.IA excedeu o tempo limite.",
        {
          cause: error,
        },
      );
    }

    if (error instanceof TypeError) {
      throw new Error(
        "Não foi possível conectar à Stav.IA. Verifique a conexão e o backend.",
        {
          cause: error,
        },
      );
    }

    throw error;
  } finally {
    window.clearTimeout(timeoutId);
  }
}
