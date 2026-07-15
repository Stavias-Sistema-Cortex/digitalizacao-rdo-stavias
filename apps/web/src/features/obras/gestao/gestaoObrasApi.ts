import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../../lib/api/apiClient";

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
  return readJson<ObraAdminApi[]>(await apiFetch(`/obras${sufixo}`));
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
