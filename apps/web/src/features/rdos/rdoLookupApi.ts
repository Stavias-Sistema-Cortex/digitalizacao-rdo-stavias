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
  serviceCatalog: RdoContextServiceCatalog[];
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

export interface RdoLocalPendingCreationContextLookup {
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
  };
  data: string;
  previousRdo: {
    id: string;
    numeroRdo: string;
    dataRdo: string;
    status: string;
  } | null;
  previousWorkforce: RdoPreviousWorkforceItem[];
  programacoes: RdoContextSchedule[];
  colaboradores: RdoContextCollaborator[];
  equipamentos: RdoContextEquipment[];
}

export type RdoDraftCreationContextLookup =
  | {
      kind: "CANONICAL";
      context: RdoCreationContextLookup;
    }
  | {
      kind: "LOCAL_PENDING";
      context: RdoLocalPendingCreationContextLookup;
    };

export const RDO_CREATION_CONTEXT_INCOMPATIBLE =
  "O servidor retornou um contexto de RDO incompatível. Atualize os serviços do Córtex e tente novamente.";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasText(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

function nullableText(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function nullableFiniteNumber(value: unknown): number | null {
  return isFiniteNumber(value) ? value : null;
}

function isCoverageSection(value: unknown): boolean {
  return isRecord(value) &&
    hasText(value.status) &&
    isFiniteNumber(value.total) &&
    isFiniteNumber(value.returned) &&
    typeof value.complete === "boolean";
}

function hasCoverage(value: unknown): boolean {
  if (!isRecord(value)) return false;
  return [
    "previousWorkforce",
    "programacoes",
    "colaboradores",
    "equipamentos",
    "serviceCatalog",
    "priceCatalog",
  ].every((key) => isCoverageSection(value[key]));
}

function hasFreshness(value: unknown): boolean {
  return isRecord(value) &&
    hasText(value.status) &&
    isFiniteNumber(value.sourceVersion) &&
    hasText(value.generatedAt) &&
    hasText(value.staleAfter);
}

function hasProvenance(value: unknown): boolean {
  return isRecord(value) &&
    isFiniteNumber(value.receiptVersion) &&
    isFiniteNumber(value.sourceVersion) &&
    hasText(value.worksiteId) &&
    hasText(value.selectedDate) &&
    hasText(value.generatedAt) &&
    (value.previousRdoId === null || typeof value.previousRdoId === "string");
}

function hasPreviousRdo(value: unknown): boolean {
  return value === null ||
    value === undefined ||
    (isRecord(value) &&
      hasText(value.id) &&
      hasText(value.numeroRdo) &&
      hasText(value.dataRdo) &&
      hasText(value.status) &&
      isFiniteNumber(value.version));
}

export function assertRdoCreationContextLookup(
  value: unknown,
): asserts value is RdoCreationContextLookup {
  if (
    !isRecord(value) ||
    !isRecord(value.obra) ||
    !hasText(value.obra.id) ||
    !hasText(value.data) ||
    !Array.isArray(value.previousWorkforce) ||
    !Array.isArray(value.programacoes) ||
    !Array.isArray(value.colaboradores) ||
    !Array.isArray(value.equipamentos) ||
    !Array.isArray(value.serviceCatalog) ||
    !hasCoverage(value.coverage) ||
    !hasFreshness(value.freshness) ||
    !hasProvenance(value.provenance) ||
    !hasPreviousRdo(value.previousRdo)
  ) {
    throw new Error(RDO_CREATION_CONTEXT_INCOMPATIBLE);
  }
}

export interface RdoContextServicePriceChoice {
  id: string;
  serviceId: string;
  unit: string;
  version: number;
  validFrom: string;
  effectiveValidTo: string | null;
}

export interface RdoContextServiceCatalog {
  id: string;
  code: string;
  name: string;
  description: string | null;
  priceChoices: RdoContextServicePriceChoice[];
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
  const context = await readJson<unknown>(response);
  assertRdoCreationContextLookup(context);
  return context;
}

const CANONICAL_CONTEXT_MARKERS = [
  "nextNumberSuggestion",
  "previousRdo",
  "previousWorkforce",
  "serviceCatalog",
  "coverage",
  "freshness",
  "provenance",
] as const;

interface LegacyRdoSummary {
  id: string;
  obraId: string;
  numeroRdo: string;
  dataRdo: string;
  status: string;
}

function assertLegacyContextIdentity(
  value: unknown,
  obraId: string,
  selectedDate: string,
): asserts value is Record<string, unknown> & {
  obra: Record<string, unknown>;
  data: string;
  programacoes: unknown[];
  colaboradores: unknown[];
  equipamentos: unknown[];
} {
  if (
    !isRecord(value) ||
    CANONICAL_CONTEXT_MARKERS.some((key) => key in value) ||
    !isRecord(value.obra) ||
    value.obra.id !== obraId ||
    value.data !== selectedDate ||
    !Array.isArray(value.programacoes) ||
    !Array.isArray(value.colaboradores) ||
    !Array.isArray(value.equipamentos)
  ) {
    throw new Error(RDO_CREATION_CONTEXT_INCOMPATIBLE);
  }
}

function legacyWorksite(
  value: Record<string, unknown>,
): RdoLocalPendingCreationContextLookup["obra"] {
  return {
    id: value.id as string,
    codigoContrato: nullableText(value.codigoContrato),
    codigoCw: nullableText(value.codigoCw),
    nome: nullableText(value.nome),
    cliente: nullableText(value.cliente),
    cidade: nullableText(value.cidade),
    uf: nullableText(value.uf),
    rodovia: nullableText(value.rodovia),
    status: nullableText(value.status),
  };
}

function legacySchedules(values: unknown[]): RdoContextSchedule[] {
  return values.map((value) => {
    if (
      !isRecord(value) ||
      !hasText(value.id) ||
      !hasText(value.dataProgramacao)
    ) {
      throw new Error(RDO_CREATION_CONTEXT_INCOMPATIBLE);
    }
    return {
      id: value.id,
      dataProgramacao: value.dataProgramacao,
      equipe: nullableText(value.equipe),
      encarregado: nullableText(value.encarregado),
      engenheiro: nullableText(value.engenheiro),
      cliente: nullableText(value.cliente),
      servico: nullableText(value.servico),
      tipoServico: nullableText(value.tipoServico),
      cidade: nullableText(value.cidade),
      uf: nullableText(value.uf),
      rodovia: nullableText(value.rodovia),
      sentido: nullableText(value.sentido),
      faixa: nullableText(value.faixa),
      kmInicial: nullableText(value.kmInicial),
      kmFinal: nullableText(value.kmFinal),
      extensaoM: nullableFiniteNumber(value.extensaoM),
      larguraM: nullableFiniteNumber(value.larguraM),
      espessuraCm: nullableFiniteNumber(value.espessuraCm),
      areaM2: nullableFiniteNumber(value.areaM2),
      volumeM3: nullableFiniteNumber(value.volumeM3),
      status: nullableText(value.status),
    };
  });
}

function legacyEquipment(values: unknown[]): RdoContextEquipment[] {
  return values.map((value) => {
    if (!isRecord(value) || !hasText(value.id)) {
      throw new Error(RDO_CREATION_CONTEXT_INCOMPATIBLE);
    }
    return {
      id: value.id,
      codigoExterno: nullableText(value.codigoExterno),
      nome: nullableText(value.nome),
      categoria: nullableText(value.categoria),
    };
  });
}

function scopedCollaborators(values: unknown): RdoContextCollaborator[] {
  if (!Array.isArray(values)) {
    throw new Error(RDO_CREATION_CONTEXT_INCOMPATIBLE);
  }
  return values.map((value) => {
    if (!isRecord(value) || !hasText(value.id)) {
      throw new Error(RDO_CREATION_CONTEXT_INCOMPATIBLE);
    }
    return {
      id: value.id,
      codigoColaborador: null,
      nome: nullableText(value.nome),
      papelNaObra: null,
      nomePerfil: nullableText(value.nomePerfil),
    };
  });
}

function legacySummaries(
  value: unknown,
  obraId: string,
  selectedDate: string,
): LegacyRdoSummary[] {
  if (!Array.isArray(value)) {
    throw new Error(RDO_CREATION_CONTEXT_INCOMPATIBLE);
  }
  return value
    .map((item): LegacyRdoSummary => {
      if (
        !isRecord(item) ||
        !hasText(item.id) ||
        item.obraId !== obraId ||
        !hasText(item.numeroRdo) ||
        !hasText(item.dataRdo) ||
        !hasText(item.status)
      ) {
        throw new Error(RDO_CREATION_CONTEXT_INCOMPATIBLE);
      }
      return {
        id: item.id,
        obraId,
        numeroRdo: item.numeroRdo,
        dataRdo: item.dataRdo,
        status: item.status,
      };
    })
    .filter((item) => item.dataRdo < selectedDate)
    .sort((left, right) =>
      right.dataRdo.localeCompare(left.dataRdo) ||
      right.id.localeCompare(left.id)
    );
}

function previousWorkforce(
  value: unknown,
  previous: LegacyRdoSummary,
  authorizedIds: ReadonlySet<string>,
): RdoPreviousWorkforceItem[] {
  if (
    !isRecord(value) ||
    value.id !== previous.id ||
    value.obraId !== previous.obraId ||
    value.dataRdo !== previous.dataRdo ||
    !Array.isArray(value.maoObra)
  ) {
    throw new Error(RDO_CREATION_CONTEXT_INCOMPATIBLE);
  }
  return value.maoObra.map((item) => {
    if (!isRecord(item) || !hasText(item.id)) {
      throw new Error(RDO_CREATION_CONTEXT_INCOMPATIBLE);
    }
    const collaboratorId = nullableText(item.colaboradorId);
    return {
      sourceItemId: item.id,
      sourceRdoId: previous.id,
      collaboratorId,
      nameSnapshot: nullableText(item.nomeColaborador),
      roleSnapshot: nullableText(item.cargo),
      linkType: nullableText(item.tipoVinculo),
      quantity: nullableFiniteNumber(item.quantidade),
      startTime: nullableText(item.horaInicio),
      endTime: nullableText(item.horaFim),
      observations: nullableText(item.observacoes),
      availability: collaboratorId === null
        ? "UNKNOWN"
        : authorizedIds.has(collaboratorId)
          ? "AVAILABLE"
          : "UNAVAILABLE",
    };
  });
}

export async function buscarContextoParaRascunhoRdo(
  obraId: string,
  dataRdo: string,
): Promise<RdoDraftCreationContextLookup> {
  const params = new URLSearchParams({ obraId, data: dataRdo });
  const response = await apiFetch(`/rdos/contexto?${params.toString()}`);
  const raw = await readJson<unknown>(response);

  try {
    assertRdoCreationContextLookup(raw);
    if (
      raw.obra.id !== obraId ||
      raw.data !== dataRdo ||
      raw.provenance.worksiteId !== obraId ||
      raw.provenance.selectedDate !== dataRdo
    ) {
      throw new Error(RDO_CREATION_CONTEXT_INCOMPATIBLE);
    }
    return { kind: "CANONICAL", context: raw };
  } catch (error) {
    if (
      !(error instanceof Error) ||
      error.message !== RDO_CREATION_CONTEXT_INCOMPATIBLE
    ) {
      throw error;
    }
  }

  assertLegacyContextIdentity(raw, obraId, dataRdo);
  const [summariesResponse, collaboratorsResponse] = await Promise.all([
    apiFetch(`/rdos?${new URLSearchParams({ obraId }).toString()}`),
    apiFetch(`/obras/${encodeURIComponent(obraId)}/colaboradores`),
  ]);
  const [summariesBody, collaboratorsBody] = await Promise.all([
    readJson<unknown>(summariesResponse),
    readJson<unknown>(collaboratorsResponse),
  ]);
  const collaborators = scopedCollaborators(collaboratorsBody);
  const previous = legacySummaries(
    summariesBody,
    obraId,
    dataRdo,
  )[0] ?? null;
  let workforce: RdoPreviousWorkforceItem[] = [];
  if (previous) {
    const detailResponse = await apiFetch(
      `/rdos/${encodeURIComponent(previous.id)}`,
    );
    const detail = await readJson<unknown>(detailResponse);
    workforce = previousWorkforce(
      detail,
      previous,
      new Set(collaborators.map((item) => item.id)),
    );
  }

  return {
    kind: "LOCAL_PENDING",
    context: {
      obra: legacyWorksite(raw.obra),
      data: dataRdo,
      previousRdo: previous
        ? {
            id: previous.id,
            numeroRdo: previous.numeroRdo,
            dataRdo: previous.dataRdo,
            status: previous.status,
          }
        : null,
      previousWorkforce: workforce,
      programacoes: legacySchedules(raw.programacoes),
      colaboradores: collaborators,
      equipamentos: legacyEquipment(raw.equipamentos),
    },
  };
}

export async function buscarObrasAutorizadasParaRdo(): Promise<
  RdoAuthorizedWorksiteLookup[]
> {
  const response = await apiFetch("/obras/relacionadas");
  return readJson<RdoAuthorizedWorksiteLookup[]>(response);
}
