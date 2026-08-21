import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../../lib/api/apiClient";

/**
 * Totais de usinagem e aplicação da obra, material a material.
 *
 * O contrato espelha o servidor: quantidade ausente permanece nula — nunca
 * vira zero —, e os campos monetários só existem quando o financeiro é
 * visível para quem consulta e o preço casou no catálogo. `precoMotivo`
 * explica a linha sem dinheiro: sem preço cadastrado, ou mais de um preço
 * vigente com o mesmo nome e unidade.
 */
export interface MaterialUsinado {
  material: string;
  unidade: string | null;
  quantidadePrevista: number | null;
  quantidadeUsinada: number | null;
  quantidadeAplicada: number | null;
  quantidadeSobra: number | null;
  quantidadeNaoAplicada: number | null;
  totalRdos: number;
  primeiraData: string | null;
  ultimaData: string | null;
  precoUnitario: number | null;
  precoMotivo: "SEM_PRECO" | "PRECO_AMBIGUO" | null;
  valorAplicado: number | null;
  valorDesperdicado: number | null;
}

export interface TotaisUsinados {
  valorAplicado: number | null;
  valorDesperdicado: number | null;
  materiaisSemPreco: number;
}

export interface ObraUsinados {
  obraId: string;
  obraNome: string;
  precosVisiveis: boolean;
  materiais: MaterialUsinado[];
  totais: TotaisUsinados | null;
}

function objectValue(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function nullableNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function nullableString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function precoMotivo(value: unknown): MaterialUsinado["precoMotivo"] {
  return value === "SEM_PRECO" || value === "PRECO_AMBIGUO" ? value : null;
}

function materialFromApi(value: unknown): MaterialUsinado | null {
  const item = objectValue(value);
  const material = nullableString(item.material);
  if (!material) {
    return null;
  }
  return {
    material,
    unidade: nullableString(item.unidade),
    quantidadePrevista: nullableNumber(item.quantidadePrevista),
    quantidadeUsinada: nullableNumber(item.quantidadeUsinada),
    quantidadeAplicada: nullableNumber(item.quantidadeAplicada),
    quantidadeSobra: nullableNumber(item.quantidadeSobra),
    quantidadeNaoAplicada: nullableNumber(item.quantidadeNaoAplicada),
    totalRdos: nullableNumber(item.totalRdos) ?? 0,
    primeiraData: nullableString(item.primeiraData),
    ultimaData: nullableString(item.ultimaData),
    precoUnitario: nullableNumber(item.precoUnitario),
    precoMotivo: precoMotivo(item.precoMotivo),
    valorAplicado: nullableNumber(item.valorAplicado),
    valorDesperdicado: nullableNumber(item.valorDesperdicado),
  };
}

export function usinadosResponseFromApi(value: unknown): ObraUsinados {
  const root = objectValue(value);
  const obraId = nullableString(root.obraId);
  const obraNome = nullableString(root.obraNome);
  if (!obraId || !obraNome) {
    throw new Error("Resposta de usinados não identifica a obra.");
  }
  const totais = objectValue(root.totais);
  return {
    obraId,
    obraNome,
    precosVisiveis: root.precosVisiveis === true,
    materiais: Array.isArray(root.materiais)
      ? root.materiais.flatMap((item) => {
          const material = materialFromApi(item);
          return material ? [material] : [];
        })
      : [],
    totais: root.totais === null || root.totais === undefined
      ? null
      : {
          valorAplicado: nullableNumber(totais.valorAplicado),
          valorDesperdicado: nullableNumber(totais.valorDesperdicado),
          materiaisSemPreco: nullableNumber(totais.materiaisSemPreco) ?? 0,
        },
  };
}

export async function buscarUsinadosObra(
  obraId: string,
): Promise<ObraUsinados> {
  const response = await apiFetch(
    `/obras/${encodeURIComponent(obraId)}/usinados`,
  );
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(body, response.status));
  }
  const usinados = usinadosResponseFromApi(body);
  if (usinados.obraId !== obraId) {
    throw new Error(
      "O servidor retornou usinados de outra obra.",
    );
  }
  return usinados;
}
