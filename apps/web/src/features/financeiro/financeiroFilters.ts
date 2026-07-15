import type {
  FinanceFilters,
  FinanceManualField,
  FinanceManualFilter,
  FinanceManualMode,
  FinanceManualOperator,
  FinanceManualRow,
} from "./financeiro.types";

const MAX_MANUAL_RULES = 20;

const MANUAL_OPERATORS: Record<FinanceManualField, readonly FinanceManualOperator[]> = {
  descricao: ["CONTAINS", "NOT_CONTAINS", "EQUALS", "STARTS_WITH"],
  numero: ["CONTAINS", "NOT_CONTAINS", "EQUALS", "STARTS_WITH"],
  fornecedor: ["CONTAINS", "NOT_CONTAINS", "EQUALS", "STARTS_WITH"],
  status: ["EQUALS", "NOT_EQUALS"],
  tipo: ["EQUALS", "NOT_EQUALS"],
  valor: ["EQUALS", "GREATER_THAN", "LESS_THAN", "BETWEEN"],
  data: ["ON", "BEFORE", "AFTER", "BETWEEN"],
};

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
  manualMode: "ALL",
  manualRules: [],
};

type QuickFilterKey = Exclude<keyof FinanceFilters, "manualMode" | "manualRules">;

const URL_KEYS: Record<QuickFilterKey, string> = {
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

const API_KEYS: Partial<Record<QuickFilterKey, string>> = {
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
  const quick = Object.fromEntries(
    (Object.keys(URL_KEYS) as QuickFilterKey[]).map((key) => [
      key,
      params.get(URL_KEYS[key])?.trim() || EMPTY_FINANCE_FILTERS[key],
    ]),
  ) as Pick<FinanceFilters, QuickFilterKey>;
  const manualMode: FinanceManualMode = params.get("fm") === "ANY"
    ? "ANY"
    : "ALL";
  const manualRules = params.getAll("f")
    .slice(0, MAX_MANUAL_RULES)
    .map(parseManualRule)
    .filter((rule): rule is FinanceManualFilter => rule !== null)
    .map((rule, index) => ({ ...rule, id: `manual-${index}` }));
  return { ...quick, manualMode, manualRules };
}

export function filtersToSearchParams(
  filters: FinanceFilters,
): URLSearchParams {
  const params = new URLSearchParams();
  for (const key of Object.keys(URL_KEYS) as QuickFilterKey[]) {
    const value = filters[key].trim();
    if (value) {
      params.set(URL_KEYS[key], value);
    }
  }
  const rules = filters.manualRules
    .slice(0, MAX_MANUAL_RULES)
    .filter(isSupportedManualRule);
  if (rules.length > 0) {
    params.set("fm", filters.manualMode === "ANY" ? "ANY" : "ALL");
    rules.forEach((rule) => params.append("f", [
      rule.field,
      rule.operator,
      rule.value.trim(),
      rule.secondValue.trim(),
    ].join("|")));
  }
  return params;
}

export function financeQueryParams(
  filters: FinanceFilters,
): URLSearchParams {
  const params = new URLSearchParams();
  for (const key of Object.keys(API_KEYS) as QuickFilterKey[]) {
    const value = filters[key].trim();
    const apiKey = API_KEYS[key];
    if (apiKey && value) {
      params.set(apiKey, value);
    }
  }
  return params;
}

function parseManualRule(serialized: string): FinanceManualFilter | null {
  if (serialized.length > 600) return null;
  const [field, operator, value = "", secondValue = ""] = serialized.split("|");
  const candidate: FinanceManualFilter = {
    id: "",
    field: field as FinanceManualField,
    operator: operator as FinanceManualOperator,
    value: value.trim().slice(0, 240),
    secondValue: secondValue.trim().slice(0, 240),
  };
  return isSupportedManualRule(candidate) ? candidate : null;
}

export function isValidManualRule(rule: FinanceManualFilter): boolean {
  if (!isSupportedManualRule(rule)) return false;
  if (!rule.value.trim()) return false;
  return rule.operator !== "BETWEEN" || Boolean(rule.secondValue.trim());
}

function isSupportedManualRule(rule: FinanceManualFilter): boolean {
  const operators = MANUAL_OPERATORS[rule.field];
  return Boolean(operators?.includes(rule.operator));
}

export function manualOperatorsFor(
  field: FinanceManualField,
): readonly FinanceManualOperator[] {
  return MANUAL_OPERATORS[field];
}

export function applyManualFilters<T extends FinanceManualRow>(
  rows: T[],
  rules: FinanceManualFilter[],
  mode: FinanceManualMode,
): T[] {
  const active = rules.filter(isValidManualRule).slice(0, MAX_MANUAL_RULES);
  if (active.length === 0) return rows;
  return rows.filter((row) => {
    const matches = active.map((rule) => matchesManualRule(row, rule));
    return mode === "ANY" ? matches.some(Boolean) : matches.every(Boolean);
  });
}

export function filterFinanceRows<T>(
  rows: T[],
  toManualRow: (row: T) => FinanceManualRow,
  rules: FinanceManualFilter[],
  mode: FinanceManualMode,
): T[] {
  const active = rules.filter(isValidManualRule).slice(0, MAX_MANUAL_RULES);
  if (active.length === 0) return rows;
  return rows.filter((row) => {
    const manualRow = toManualRow(row);
    const matches = active.map((rule) => matchesManualRule(manualRow, rule));
    return mode === "ANY" ? matches.some(Boolean) : matches.every(Boolean);
  });
}

function matchesManualRule(
  row: FinanceManualRow,
  rule: FinanceManualFilter,
): boolean {
  if (rule.field === "valor") {
    return compareNumber(row.valor, rule);
  }
  if (rule.field === "data") {
    return compareDate(row.data, rule);
  }
  return compareText(row[rule.field], rule);
}

function compareText(value: string, rule: FinanceManualFilter): boolean {
  const actual = normalizeText(value);
  const expected = normalizeText(rule.value);
  if (rule.operator === "CONTAINS") return actual.includes(expected);
  if (rule.operator === "NOT_CONTAINS") return !actual.includes(expected);
  if (rule.operator === "EQUALS") return actual === expected;
  if (rule.operator === "NOT_EQUALS") return actual !== expected;
  return rule.operator === "STARTS_WITH" && actual.startsWith(expected);
}

function compareNumber(value: number, rule: FinanceManualFilter): boolean {
  const first = Number(rule.value.replace(",", "."));
  const second = Number(rule.secondValue.replace(",", "."));
  if (!Number.isFinite(value) || !Number.isFinite(first)) return false;
  if (rule.operator === "EQUALS") return value === first;
  if (rule.operator === "GREATER_THAN") return value > first;
  if (rule.operator === "LESS_THAN") return value < first;
  return rule.operator === "BETWEEN" && Number.isFinite(second) &&
    value >= Math.min(first, second) && value <= Math.max(first, second);
}

function compareDate(value: string, rule: FinanceManualFilter): boolean {
  const actual = Date.parse(value);
  const first = Date.parse(rule.value);
  const second = Date.parse(rule.secondValue);
  if (!Number.isFinite(actual) || !Number.isFinite(first)) return false;
  if (rule.operator === "ON") return value.slice(0, 10) === rule.value.slice(0, 10);
  if (rule.operator === "BEFORE") return actual < first;
  if (rule.operator === "AFTER") return actual > first;
  return rule.operator === "BETWEEN" && Number.isFinite(second) &&
    actual >= Math.min(first, second) && actual <= Math.max(first, second);
}

function normalizeText(value: string): string {
  return value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLocaleLowerCase("pt-BR").trim();
}
