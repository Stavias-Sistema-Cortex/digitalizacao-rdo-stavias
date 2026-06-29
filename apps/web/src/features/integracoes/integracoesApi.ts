import type {
  IntegracaoActionResponse,
  IntegracaoStatus,
} from "./integracoes.types";
import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";

async function readJson<T>(
  response: Response,
  invalidMessage: string,
): Promise<T> {
  const body = await readResponseBody(response);

  if (!response.ok) {
    throw new Error(
      responseErrorMessage(body, response.status),
    );
  }

  if (body === null) {
    throw new Error(invalidMessage);
  }

  return body as T;
}

export async function listarIntegracoes(): Promise<
  IntegracaoStatus[]
> {
  const response = await apiFetch("/integracoes");

  return readJson<IntegracaoStatus[]>(
    response,
    "O backend retornou uma lista de integrações inválida.",
  );
}

export async function testarConexaoIntegracao(
  id: string,
): Promise<IntegracaoActionResponse> {
  const response = await apiFetch(
    `/integracoes/${id}/testar-conexao`,
    {
      method: "POST",
    },
  );

  return readJson<IntegracaoActionResponse>(
    response,
    "O backend retornou um teste de conexão inválido.",
  );
}

export async function sincronizarIntegracao(
  id: string,
): Promise<IntegracaoActionResponse> {
  const response = await apiFetch(
    `/integracoes/${id}/sincronizar`,
    {
      method: "POST",
    },
  );

  return readJson<IntegracaoActionResponse>(
    response,
    "O backend retornou uma sincronização inválida.",
  );
}
