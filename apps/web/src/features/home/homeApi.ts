import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";

export interface ObraRelacionadaApi {
  id: string;
  codigoContrato: string | null;
  nome: string | null;
  cliente: string | null;
  cidade: string | null;
  uf: string | null;
  rodovia: string | null;
  status: string | null;
  observacoes: string | null;
  latitude: number | string | null;
  longitude: number | string | null;
  valorContratual: number | string | null;
  atualizadoEm: string | null;
}

export interface PrevisaoHistoricoApi {
  id: string;
  obraId: string;
  dataReferencia: string | null;
  statusExecucao: string | null;
  producaoPlanejada: number | string | null;
  producaoRealizada: number | string | null;
  custoRealizado: number | string | null;
  custoPrevistoFinal: number | string | null;
  receitaPrevistaFinal: number | string | null;
}

async function readJson<T>(response: Response): Promise<T> {
  const data = await readResponseBody(response);

  if (!response.ok) {
    throw new Error(
      responseErrorMessage(data, response.status),
    );
  }

  return data as T;
}

export async function buscarObrasRelacionadas(): Promise<
  ObraRelacionadaApi[]
> {
  const response = await apiFetch("/obras/relacionadas");
  return readJson<ObraRelacionadaApi[]>(response);
}

export async function buscarHistoricoPrevisao(
  obraId: string,
  page = 0,
  size = 100,
): Promise<PrevisaoHistoricoApi[]> {
  const response = await apiFetch(
    `/obras/${encodeURIComponent(obraId)}/previsao-financeira/historico?page=${page}&size=${size}`,
  );
  return readJson<PrevisaoHistoricoApi[]>(response);
}
