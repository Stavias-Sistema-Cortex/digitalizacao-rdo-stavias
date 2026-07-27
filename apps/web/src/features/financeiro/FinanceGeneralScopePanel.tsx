import { useState } from "react";

import { criarUnidadeAdministrativa } from "./financeiroApi";
import type {
  FinanceControlUnit,
  FinanceControlUnitType,
} from "./financeiro.types";

const TYPE_LABELS: Record<FinanceControlUnitType, string> = {
  OBRA: "Obra",
  ATIVO: "Equipamento",
  ADMINISTRATIVO: "Administrativo",
  CORPORATIVO: "Corporativo",
};

export function FinanceGeneralScopePanel({
  units,
  selectedUnitId,
  canAdminister,
  onSelect,
  onChanged,
}: {
  units: FinanceControlUnit[];
  selectedUnitId: string;
  canAdminister: boolean;
  onSelect: (unit: FinanceControlUnit) => void;
  onChanged: () => void;
}) {
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState("");

  async function create(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const code = String(form.get("codigo") ?? "").trim().toUpperCase();
    const name = String(form.get("nome") ?? "").trim();
    if (!code || !name) {
      setError("Informe código e nome da unidade administrativa.");
      return;
    }
    setError("");
    try {
      await criarUnidadeAdministrativa(code, name);
      setCreating(false);
      onChanged();
    } catch (reason: unknown) {
      setError(reason instanceof Error
        ? reason.message
        : "Não foi possível criar a unidade administrativa.");
    }
  }

  return (
    <section
      className="finance-workspace-section finance-unit-catalog"
      aria-label="Unidades financeiras autorizadas"
    >
      <div className="finance-section-heading">
        <div>
          <p className="finance-kicker">Unidades de controle</p>
          <h2>Selecione uma unidade para abrir sua visão financeira</h2>
          <p>
            Cada lançamento permanece ligado à sua obra, equipamento ou
            unidade administrativa de origem.
          </p>
        </div>
        <div className="finance-unit-catalog__tools">
          <span>
            {units.length} {units.length === 1 ? "unidade" : "unidades"} no escopo
          </span>
          {canAdminister ? (
            <button
              type="button"
              className="finance-secondary-action"
              onClick={() => setCreating((value) => !value)}
            >
              Nova unidade administrativa
            </button>
          ) : null}
        </div>
      </div>

      {creating ? (
        <form className="finance-unit-create" onSubmit={create}>
          <label>
            Código
            <input name="codigo" maxLength={120} placeholder="ADM-SEDE" />
          </label>
          <label>
            Nome
            <input name="nome" maxLength={255} placeholder="Administração central" />
          </label>
          <button type="submit" className="finance-primary-action">Criar unidade</button>
          {error ? <p className="finance-form-error" role="alert">{error}</p> : null}
        </form>
      ) : null}

      {units.length === 0 ? (
        <p className="finance-row-empty">
          Nenhuma unidade financeira autorizada foi encontrada.
        </p>
      ) : (
        <div className="finance-unit-grid">
          {units.map((unit) => (
            <button
              key={unit.id}
              type="button"
              className={unit.id === selectedUnitId ? "is-selected" : ""}
              aria-pressed={unit.id === selectedUnitId}
              aria-label={`${TYPE_LABELS[unit.tipo]}: ${unit.nome} · ${unit.codigo}`}
              onClick={() => onSelect(unit)}
            >
              <span className="finance-unit-card__kind">
                {TYPE_LABELS[unit.tipo]}
              </span>
              <strong>{unit.nome}</strong>
              <small className="finance-unit-card__code">{unit.codigo}</small>
              <span className="finance-unit-card__action">
                Abrir unidade <span aria-hidden="true">↗</span>
              </span>
            </button>
          ))}
        </div>
      )}
    </section>
  );
}
