import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../../lib/api/apiClient";
import { getSession } from "../../auth/authSession";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export interface ObraAdminApi {
  id: string;
  codigoContrato: string | null;
  codigoCw: string | null;
  codigoInterno: string | null;
  nome: string | null;
  cliente: string | null;
  cidade: string | null;
  uf: string | null;
  rodovia: string | null;
  status: string | null;
  atualizadoEm: string | null;
  arquivadoEm: string | null;
  versaoLinha: number | null;
}

export interface ColaboradorApi {
  id: string;
  nome: string | null;
  cpfMascarado: string | null;
  nomeGrupo: string | null;
  nomePerfil: string | null;
  papelAcesso: "ALFA" | "BETA";
  ativo: boolean;
}

export interface AdminRoleChangeApi {
  colaboradorId: string;
  nome: string | null;
  papelAnterior: "ALFA" | "BETA";
  papelAcesso: "ALFA" | "BETA";
  sessoesRevogadas: number;
  commitSeq: number;
  alteradoEm: string;
}

export interface VinculoApi {
  id: string;
  obraId: string;
  colaboradorId: string;
  colaboradorNome: string | null;
  status: string;
  papelNaObra: string | null;
  atribuidoEm: string | null;
  atribuidoPor: string | null;
  revogadoEm: string | null;
  revogadoPor: string | null;
  versaoEntidade?: number;
}

export interface PendingVinculoApi extends VinculoApi {
  status: "PENDENTE";
  syncStatus: "PENDING_SYNC";
  pendingMutationId: string;
}

export interface NovaObraInput {
  codigoContrato: string;
  nome: string;
  cliente?: string;
  cidade?: string;
  uf?: string;
  rodovia?: string;
}

async function readJson<T>(response: Response): Promise<T> {
  const data = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(data, response.status));
  }
  return data as T;
}

/**
 * Valida o formulário de criação de obra no cliente (o backend valida de novo).
 * Retorna a lista de erros em português; vazia quando válido.
 */
export function validarNovaObra(input: NovaObraInput): string[] {
  const erros: string[] = [];
  if (!input.codigoContrato.trim()) {
    erros.push("Informe o código do contrato.");
  }
  if (!input.nome.trim()) {
    erros.push("Informe o nome da obra.");
  }
  const uf = input.uf?.trim() ?? "";
  if (uf && uf.length !== 2) {
    erros.push("UF deve ter exatamente 2 caracteres.");
  }
  return erros;
}

export async function listarObrasAdmin(
  query?: string,
): Promise<ObraAdminApi[]> {
  const sufixo = query?.trim()
    ? `?query=${encodeURIComponent(query.trim())}`
    : "";
  const response = await readJson<ObraAdminApi[]>(
    await apiFetch(`/obras${sufixo}`),
  );
  return filtrarObrasOperacionais(response);
}

export function filtrarObrasOperacionais(
  obras: readonly ObraAdminApi[],
): ObraAdminApi[] {
  return obras.filter((obra) => obra.arquivadoEm == null);
}

export async function criarObra(
  input: NovaObraInput,
): Promise<ObraAdminApi> {
  return readJson<ObraAdminApi>(
    await apiFetch("/obras", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        codigoContrato: input.codigoContrato.trim(),
        nome: input.nome.trim(),
        cliente: input.cliente?.trim() || null,
        cidade: input.cidade?.trim() || null,
        uf: input.uf?.trim() || null,
        rodovia: input.rodovia?.trim() || null,
      }),
    }),
  );
}

export async function listarColaboradores(
  query?: string,
): Promise<ColaboradorApi[]> {
  const sufixo = query?.trim()
    ? `?query=${encodeURIComponent(query.trim())}`
    : "";
  return readJson<ColaboradorApi[]>(
    await apiFetch(`/colaboradores${sufixo}`),
  );
}

export async function alterarPapelColaborador(
  colaboradorId: string,
  papelAcesso: "ALFA" | "BETA",
  justificativa: string,
): Promise<AdminRoleChangeApi> {
  return readJson<AdminRoleChangeApi>(
    await apiFetch(
      `/admin/colaboradores/${encodeURIComponent(colaboradorId)}/papel`,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          papelAcesso,
          justificativa: justificativa.trim(),
        }),
      },
    ),
  );
}

export async function listarVinculos(
  obraId: string,
): Promise<VinculoApi[]> {
  return readJson<VinculoApi[]>(
    await apiFetch(`/obras/${encodeURIComponent(obraId)}/vinculos`),
  );
}

export async function vincularColaborador(
  obraId: string,
  colaboradorId: string,
  papelNaObra?: string,
): Promise<VinculoApi> {
  return readJson<VinculoApi>(
    await apiFetch(`/obras/${encodeURIComponent(obraId)}/vinculos`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        colaboradorId,
        papelNaObra: papelNaObra?.trim() || null,
      }),
    }),
  );
}

async function governanceMutationIdentity(): Promise<{
  userId: string;
  deviceId: string;
}> {
  const session = getSession();
  if (
    !session ||
    session.papelAcesso !== "ALFA" ||
    !session.escopoGlobal
  ) {
    throw new Error(
      "A gestão de vínculos exige uma sessão Alfa com escopo global.",
    );
  }
  const { getSyncState, updateSyncState } = await import(
    "../../../lib/db/syncStateRepository"
  );
  const state = await getSyncState();
  const deviceId =
    state.usuarioId === session.colaboradorId &&
      state.deviceId &&
      UUID_PATTERN.test(state.deviceId)
      ? state.deviceId
      : crypto.randomUUID();
  if (
    state.deviceId !== deviceId ||
    state.usuarioId !== session.colaboradorId
  ) {
    await updateSyncState({
      deviceId,
      usuarioId: session.colaboradorId,
      lastPulledCommitSeq: 0,
      lastAckedCommitSeq: 0,
    });
  }
  return { userId: session.colaboradorId, deviceId };
}

export async function queueVinculoColaborador(
  obraId: string,
  colaboradorId: string,
  papelNaObra?: string,
): Promise<PendingVinculoApi> {
  const identity = await governanceMutationIdentity();
  const id = crypto.randomUUID();
  const timestamp = new Date().toISOString();
  const pending: PendingVinculoApi = {
    id,
    obraId,
    colaboradorId,
    colaboradorNome: null,
    status: "PENDENTE",
    papelNaObra: papelNaObra?.trim() || null,
    atribuidoEm: timestamp,
    atribuidoPor: identity.userId,
    revogadoEm: null,
    revogadoPor: null,
    versaoEntidade: 0,
    syncStatus: "PENDING_SYNC",
    pendingMutationId: id,
  };

  const { commitLocalMutation } = await import(
    "../../../lib/sync/localMutationCoordinator"
  );
  await commitLocalMutation({
    ...identity,
    clientMutationId: id,
    obraId,
    entityType: "VINCULO_OBRA",
    entityId: id,
    entityName: null,
    operation: "CREATE",
    transportOperation: "VINCULAR_COLABORADOR_OBRA",
    baseVersion: null,
    occurredAt: timestamp,
    previousSnapshot: {},
    nextSnapshot: {
      id,
      obraId,
      colaboradorId,
      papelNaObra: pending.papelNaObra,
      status: "PENDENTE",
    },
    eventType: "VINCULO_OBRA_ATRIBUIDO",
    colaboradorId,
    relatedEntities: [
      { tipo: "OBRA", id: obraId, nome: null },
      { tipo: "COLABORADOR", id: colaboradorId, nome: null },
    ],
    write: () => [],
  });
  return pending;
}

export async function queueRevogarVinculo(
  vinculo: VinculoApi,
): Promise<PendingVinculoApi> {
  if (!Number.isSafeInteger(vinculo.versaoEntidade)) {
    throw new Error(
      "Atualize os vínculos antes de revogar: a versão confirmada é obrigatória.",
    );
  }
  const identity = await governanceMutationIdentity();
  const timestamp = new Date().toISOString();
  const pending: PendingVinculoApi = {
    ...vinculo,
    status: "PENDENTE",
    revogadoEm: timestamp,
    revogadoPor: identity.userId,
    syncStatus: "PENDING_SYNC",
    pendingMutationId: crypto.randomUUID(),
  };

  const { commitLocalMutation } = await import(
    "../../../lib/sync/localMutationCoordinator"
  );
  await commitLocalMutation({
    ...identity,
    clientMutationId: pending.pendingMutationId,
    obraId: vinculo.obraId,
    entityType: "VINCULO_OBRA",
    entityId: vinculo.id,
    entityName: vinculo.colaboradorNome,
    operation: "DELETE",
    transportOperation: "REVOGAR_VINCULO_COLABORADOR_OBRA",
    baseVersion: vinculo.versaoEntidade!,
    occurredAt: timestamp,
    previousSnapshot: {
      id: vinculo.id,
      obraId: vinculo.obraId,
      colaboradorId: vinculo.colaboradorId,
      papelNaObra: vinculo.papelNaObra,
      status: vinculo.status,
    },
    nextSnapshot: {
      id: vinculo.id,
      obraId: vinculo.obraId,
      colaboradorId: vinculo.colaboradorId,
      papelNaObra: vinculo.papelNaObra,
      status: "REVOGADO",
      revogadoEm: timestamp,
    },
    eventType: "VINCULO_OBRA_REVOGADO",
    colaboradorId: vinculo.colaboradorId,
    relatedEntities: [
      { tipo: "OBRA", id: vinculo.obraId, nome: null },
      {
        tipo: "COLABORADOR",
        id: vinculo.colaboradorId,
        nome: vinculo.colaboradorNome,
      },
    ],
    write: () => [],
  });
  return pending;
}

export async function revogarVinculo(
  obraId: string,
  colaboradorId: string,
): Promise<VinculoApi> {
  return readJson<VinculoApi>(
    await apiFetch(
      `/obras/${encodeURIComponent(obraId)}/vinculos/${encodeURIComponent(colaboradorId)}`,
      { method: "DELETE" },
    ),
  );
}
