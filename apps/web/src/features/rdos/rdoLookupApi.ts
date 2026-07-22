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

export interface RdoContextCoverageSection {
  status: "COMPLETE" | "NOT_CONFIGURED" | string;
  total: number;
  returned: number;
  complete: boolean;
}

export interface RdoCreationContextLookup {
  obra: {
    id: string;
    codigoContrato: string | null;
    codigoCw: string | null;
    nome: string | null;
    cliente: string | null;
    cidade: string | null;
    uf: string | null;
    rodovia: string | null;
    status: string | null;
    version: number;
  };
  data: string;
  nextNumberSuggestion: string | null;
  previousRdo: {
    id: string;
    numeroRdo: string;
    dataRdo: string;
    status: string;
    version: number;
  } | null;
  previousWorkforce: RdoPreviousWorkforceItem[];
  programacoes: RdoContextSchedule[];
  colaboradores: RdoContextCollaborator[];
  equipamentos: RdoContextEquipment[];
  coverage: {
    previousWorkforce: RdoContextCoverageSection;
    programacoes: RdoContextCoverageSection;
    colaboradores: RdoContextCoverageSection;
    equipamentos: RdoContextCoverageSection;
    serviceCatalog: RdoContextCoverageSection;
    priceCatalog: RdoContextCoverageSection;
  };
  freshness: {
    status: string;
    sourceVersion: number;
    generatedAt: string;
    staleAfter: string;
  };
  provenance: {
    receiptVersion: number;
    sourceVersion: number;
    worksiteId: string;
    selectedDate: string;
    previousRdoId: string | null;
    generatedAt: string;
  };
  [key: string]: unknown;
}

export interface RdoPreviousWorkforceItem {
  sourceItemId: string;
  sourceRdoId: string;
  collaboratorId: string | null;
  nameSnapshot: string | null;
  roleSnapshot: string | null;
  linkType: string | null;
  quantity: number | null;
  startTime: string | null;
  endTime: string | null;
  observations: string | null;
  availability: string | null;
}

export interface RdoContextCollaborator {
  id: string;
  codigoColaborador: string | null;
  nome: string | null;
  papelNaObra: string | null;
  nomePerfil: string | null;
}

export interface RdoContextEquipment {
  id: string;
  codigoExterno: string | null;
  nome: string | null;
  categoria: string | null;
}

export interface RdoContextSchedule {
  id: string;
  dataProgramacao: string;
  equipe: string | null;
  encarregado: string | null;
  engenheiro: string | null;
  cliente: string | null;
  servico: string | null;
  tipoServico: string | null;
  cidade: string | null;
  uf: string | null;
  rodovia: string | null;
  sentido: string | null;
  faixa: string | null;
  kmInicial: string | null;
  kmFinal: string | null;
  extensaoM: number | null;
  larguraM: number | null;
  espessuraCm: number | null;
  areaM2: number | null;
  volumeM3: number | null;
  status: string | null;
}

export interface RdoAuthorizedWorksiteLookup {
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

export async function buscarContextoDeCriacaoRdo(
  obraId: string,
  dataRdo: string,
): Promise<RdoCreationContextLookup> {
  const params = new URLSearchParams({
    obraId,
    data: dataRdo,
  });
  const response = await apiFetch(
    `/rdos/contexto?${params.toString()}`,
  );
  return readJson<RdoCreationContextLookup>(response);
}

export async function buscarObrasAutorizadasParaRdo(): Promise<
  RdoAuthorizedWorksiteLookup[]
> {
  const response = await apiFetch("/obras/relacionadas");
  return readJson<RdoAuthorizedWorksiteLookup[]>(response);
}
