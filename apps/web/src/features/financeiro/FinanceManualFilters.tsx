import type {
  FinanceManualField,
  FinanceManualFilter,
  FinanceManualMode,
  FinanceManualOperator,
} from "./financeiro.types";
import { manualOperatorsFor } from "./financeiroFilters";

const FIELDS: { value: FinanceManualField; label: string }[] = [
  { value: "descricao", label: "Descrição" },
  { value: "numero", label: "Número / documento" },
  { value: "fornecedor", label: "Fornecedor" },
  { value: "status", label: "Status" },
  { value: "tipo", label: "Tipo" },
  { value: "valor", label: "Valor" },
  { value: "data", label: "Data" },
];

const OPERATOR_LABELS: Record<FinanceManualOperator, string> = {
  CONTAINS: "contém",
  NOT_CONTAINS: "não contém",
  EQUALS: "é igual a",
  NOT_EQUALS: "não é",
  STARTS_WITH: "começa com",
  GREATER_THAN: "é maior que",
  LESS_THAN: "é menor que",
  BETWEEN: "está entre",
  ON: "é em",
  BEFORE: "é antes de",
  AFTER: "é depois de",
};

interface FinanceManualFiltersProps {
  mode: FinanceManualMode;
  rules: FinanceManualFilter[];
  onModeChange: (mode: FinanceManualMode) => void;
  onRulesChange: (rules: FinanceManualFilter[]) => void;
}

export function FinanceManualFilters({
  mode,
  rules,
  onModeChange,
  onRulesChange,
}: FinanceManualFiltersProps) {
  function addRule() {
    if (rules.length >= 20) return;
    onRulesChange([...rules, {
      id: crypto.randomUUID(),
      field: "descricao",
      operator: "CONTAINS",
      value: "",
      secondValue: "",
    }]);
  }

  function patchRule(id: string, patch: Partial<FinanceManualFilter>) {
    onRulesChange(rules.map((rule) => rule.id === id
      ? { ...rule, ...patch }
      : rule));
  }

  return (
    <section className="finance-manual-filters" aria-label="Filtros manuais">
      <header>
        <div>
          <strong>Filtros adicionais</strong>
          <span>Combine até 20 regras; nenhuma regra aceita código ou SQL.</span>
        </div>
        <button
          type="button"
          className="finance-secondary-action"
          onClick={addRule}
          disabled={rules.length >= 20}
        >
          Adicionar filtro
        </button>
      </header>

      {rules.length > 0 ? (
        <div className="finance-manual-filters__body">
          <label className="finance-filter-mode">
            Combinar
            <select
              value={mode}
              onChange={(event) => onModeChange(
                event.target.value === "ANY" ? "ANY" : "ALL",
              )}
            >
              <option value="ALL">todas as regras</option>
              <option value="ANY">qualquer regra</option>
            </select>
          </label>
          <div className="finance-filter-rules">
            {rules.map((rule, index) => (
              <div className="finance-filter-rule" key={rule.id}>
                <span>{index + 1}</span>
                <select
                  aria-label={`Campo do filtro ${index + 1}`}
                  value={rule.field}
                  onChange={(event) => {
                    const field = event.target.value as FinanceManualField;
                    patchRule(rule.id, {
                      field,
                      operator: manualOperatorsFor(field)[0],
                      value: "",
                      secondValue: "",
                    });
                  }}
                >
                  {FIELDS.map((field) => (
                    <option key={field.value} value={field.value}>
                      {field.label}
                    </option>
                  ))}
                </select>
                <select
                  aria-label={`Operador do filtro ${index + 1}`}
                  value={rule.operator}
                  onChange={(event) => patchRule(rule.id, {
                    operator: event.target.value as FinanceManualOperator,
                    secondValue: "",
                  })}
                >
                  {manualOperatorsFor(rule.field).map((operator) => (
                    <option key={operator} value={operator}>
                      {OPERATOR_LABELS[operator]}
                    </option>
                  ))}
                </select>
                <input
                  aria-label={`Valor do filtro ${index + 1}`}
                  type={inputType(rule.field)}
                  value={rule.value}
                  onChange={(event) => patchRule(rule.id, {
                    value: event.target.value,
                  })}
                />
                {rule.operator === "BETWEEN" ? (
                  <input
                    aria-label={`Valor final do filtro ${index + 1}`}
                    type={inputType(rule.field)}
                    value={rule.secondValue}
                    onChange={(event) => patchRule(rule.id, {
                      secondValue: event.target.value,
                    })}
                  />
                ) : null}
                <button
                  type="button"
                  aria-label={`Remover filtro ${index + 1}`}
                  onClick={() => onRulesChange(
                    rules.filter((candidate) => candidate.id !== rule.id),
                  )}
                >
                  Remover
                </button>
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </section>
  );
}

function inputType(field: FinanceManualField): "text" | "number" | "date" {
  if (field === "valor") return "number";
  if (field === "data") return "date";
  return "text";
}
