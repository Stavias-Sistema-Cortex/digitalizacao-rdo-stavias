import type { FinanceFilters } from "./financeiro.types";

export const EMPTY_FINANCE_FILTERS: FinanceFilters = {
  obraId: "",
  de: "",
  ate: "",
  fornecedorId: "",
  centroCustoId: "",
  categoriaId: "",
  statusId: "",
  prioridade: "",
  tipo: "",
  moeda: "BRL",
  query: "",
};

const URL_KEYS: Record<keyof FinanceFilters, string> = {
  obraId: "obra",
  de: "de",
  ate: "ate",
  fornecedorId: "fornecedor",
  centroCustoId: "centro",
  categoriaId: "categoria",
  statusId: "status",
  prioridade: "prioridade",
  tipo: "tipo",
  moeda: "moeda",
  query: "q",
};

const API_KEYS: Partial<Record<keyof FinanceFilters, string>> = {
  obraId: "obraId",
  de: "de",
  ate: "ate",
  fornecedorId: "fornecedorId",
  centroCustoId: "centroCustoId",
  categoriaId: "categoriaId",
  statusId: "statusId",
  prioridade: "prioridade",
  tipo: "tipo",
  moeda: "moeda",
  query: "query",
};

export function filtersFromSearchParams(
  params: URLSearchParams,
): FinanceFilters {
  return Object.fromEntries(
    (Object.keys(URL_KEYS) as (keyof FinanceFilters)[]).map((key) => [
      key,
      params.get(URL_KEYS[key])?.trim() || EMPTY_FINANCE_FILTERS[key],
    ]),
  ) as unknown as FinanceFilters;
}

export function filtersToSearchParams(
  filters: FinanceFilters,
): URLSearchParams {
  const params = new URLSearchParams();
  for (const key of Object.keys(URL_KEYS) as (keyof FinanceFilters)[]) {
    const value = filters[key].trim();
    if (value) {
      params.set(URL_KEYS[key], value);
    }
  }
  return params;
}

export function financeQueryParams(
  filters: FinanceFilters,
): URLSearchParams {
  const params = new URLSearchParams();
  for (const key of Object.keys(API_KEYS) as (keyof FinanceFilters)[]) {
    const value = filters[key].trim();
    const apiKey = API_KEYS[key];
    if (apiKey && value) {
      params.set(apiKey, value);
    }
  }
  return params;
}
