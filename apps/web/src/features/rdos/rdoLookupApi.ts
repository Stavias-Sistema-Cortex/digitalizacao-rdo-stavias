import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";

export interface ColaboradorLookup {
  id: string;
  codigoColaborador: string | null;
  cpfMascarado: string | null;
  nome: string | null;
  email: string | null;
  nomeGrupo: string | null;
  nomePerfil: string | null;
  ativo: boolean;
  atualizadoEm: string | null;
}

export interface AssetLookup {
  id: string;
  externalCode: string | null;
  name: string | null;
  category: string | null;
  active: boolean | null;
  updatedAt: string | null;
}

async function readJson<T>(response: Response): Promise<T> {
  const data = await readResponseBody(response);

  if (!response.ok) {
    throw new Error(
      responseErrorMessage(
        data,
        response.status,
      ),
    );
  }

  return data as T;
}

export async function buscarColaboradores(
  query: string,
): Promise<ColaboradorLookup[]> {
  const params = new URLSearchParams();
  if (query.trim()) {
    params.set("query", query.trim());
  }

  const response = await apiFetch(
    `/colaboradores?${params.toString()}`,
  );
  return readJson<ColaboradorLookup[]>(response);
}

export interface ColaboradorDaObra {
  id: string;
  nome: string | null;
  cpfMascarado: string | null;
  nomePerfil: string | null;
  nomeGrupo: string | null;
}

/**
 * Colaboradores da obra (escopados por vínculo). Diferente de
 * {@link buscarColaboradores}, que é o catálogo global administrativo, este
 * endpoint funciona para o usuário Beta na obra a que tem acesso.
 */
export async function buscarColaboradoresDaObra(
  obraId: string,
): Promise<ColaboradorDaObra[]> {
  const response = await apiFetch(
    `/obras/${encodeURIComponent(obraId)}/colaboradores`,
  );
  return readJson<ColaboradorDaObra[]>(response);
}

export async function buscarAssets(
  query: string,
): Promise<AssetLookup[]> {
  const params = new URLSearchParams();
  if (query.trim()) {
    params.set("query", query.trim());
  }

  const response = await apiFetch(
    `/assets?${params.toString()}`,
  );
  return readJson<AssetLookup[]>(response);
}
