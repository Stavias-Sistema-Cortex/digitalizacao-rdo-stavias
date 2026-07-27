import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";
import type {
  StaviaContextDocument,
  StaviaConsultaRequest,
  StaviaConsultaResponse,
  StaviaSnapshot,
} from "./stavia.types";

export class StaviaApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "StaviaApiError";
    this.status = status;
  }
}

export async function consultarStavia(
  request: StaviaConsultaRequest,
): Promise<StaviaConsultaResponse> {
  const response = await apiFetch("/stavia/consultas", {
    method: "POST",
    timeoutMs: 60_000,
    timeoutErrorMessage:
      "A consulta à Stav.IA excedeu o tempo limite. O modelo local pode ainda estar carregando; tente novamente em alguns instantes.",
    connectionErrorMessage:
      "Não foi possível conectar à Stav.IA local. Verifique se o backend do Córtex está rodando neste dispositivo ou na rede da obra.",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });

  const body = await readResponseBody(response);

  if (!response.ok) {
    throw new Error(
      `Falha na consulta ${response.status}: ${responseErrorMessage(
        body,
        response.status,
      )}`,
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
}

export async function adicionarContextoStavia({
  obraId,
  arquivo,
  usuarioId,
  descricao,
}: {
  obraId: string;
  arquivo: File;
  usuarioId: string;
  descricao?: string;
}): Promise<StaviaContextDocument> {
  const formData = new FormData();
  formData.append("arquivo", arquivo);
  formData.append("usuarioId", usuarioId);

  if (descricao?.trim()) {
    formData.append("descricao", descricao.trim());
  }

  const response = await apiFetch(
    `/stavia/obras/${encodeURIComponent(
      obraId,
    )}/contextos`,
    {
      method: "POST",
      connectionErrorMessage:
        "Não foi possível anexar contexto porque a Stav.IA local não está acessível.",
      body: formData,
    },
  );

  const body = await readResponseBody(response);

  if (!response.ok) {
    throw new Error(
      `Falha ao adicionar contexto ${response.status}: ${responseErrorMessage(
        body,
        response.status,
      )}`,
    );
  }

  if (
    typeof body !== "object" ||
    body === null ||
    !("id" in body) ||
    !("nomeArquivo" in body)
  ) {
    throw new Error(
      "O backend retornou uma resposta inválida para o contexto da Stav.IA.",
    );
  }

  return body as StaviaContextDocument;
}

export async function baixarSnapshotStavia(): Promise<StaviaSnapshot> {
  const response = await apiFetch("/stavia/snapshot", {
    method: "GET",
    timeoutMs: 45_000,
    timeoutErrorMessage:
      "A atualização da base da Stav.IA excedeu o tempo limite.",
    connectionErrorMessage:
      "Não foi possível atualizar a base da Stav.IA agora. Os dados locais continuam disponíveis.",
  });

  const body = await readResponseBody(response);

  if (!response.ok) {
    throw new StaviaApiError(
      `Falha ao atualizar base ${response.status}: ${responseErrorMessage(
        body,
        response.status,
      )}`,
      response.status,
    );
  }

  if (
    typeof body !== "object" ||
    body === null ||
    !("metadata" in body) ||
    !("rdos" in body)
  ) {
    throw new Error(
      "O backend retornou um snapshot inválido para a Stav.IA.",
    );
  }

  return body as StaviaSnapshot;
}
