import { useState } from "react";

import { salvarCentroCusto } from "./financeiroApi";
import type {
  FinanceCostCenter,
  FinancialPermission,
} from "./financeiro.types";
import { hasFinancialPermission } from "./financeiroView";
import { FinanceDrawer } from "./FinanceDrawer";

export function FinanceCostCentersPanel({
  obraId,
  costCenters,
  permissions,
  onChanged,
}: {
  obraId: string;
  costCenters: FinanceCostCenter[];
  permissions: FinancialPermission[];
  onChanged: () => void;
}) {
  const [editing, setEditing] = useState<FinanceCostCenter | "new" | null>(null);
  const canAdmin = hasFinancialPermission(permissions, "FINANCEIRO_ADMINISTRAR");

  return (
    <section className="finance-workspace-section">
      <div className="finance-section-heading">
        <div>
          <p className="finance-kicker">Estrutura da obra</p>
          <h2>Centros de custo</h2>
          <p>Responsabilidade e classificação usadas pelos movimentos reais.</p>
        </div>
        {canAdmin && (
          <button type="button" className="finance-primary-action" onClick={() => setEditing("new")}>
            Novo centro de custo
          </button>
        )}
      </div>
      {costCenters.length === 0 ? (
        <div className="finance-row-empty">Nenhum centro de custo configurado nesta obra.</div>
      ) : (
        <div className="finance-cost-center-list">
          {costCenters.map((center) => (
            <article key={center.id}>
              <span>{center.codigo}</span>
              <div><h3>{center.nome}</h3><p>{center.descricao || "Sem descrição"}</p></div>
              <b className={`finance-pill is-${center.status === "ATIVO" ? "success" : "neutral"}`}>{center.status}</b>
              {canAdmin && <button type="button" onClick={() => setEditing(center)}>Editar</button>}
            </article>
          ))}
        </div>
      )}
      {editing && (
        <FinanceDrawer title={editing === "new" ? "Novo centro de custo" : "Editar centro de custo"} onClose={() => setEditing(null)}>
          <CostCenterForm
            obraId={obraId}
            current={editing === "new" ? null : editing}
            onSaved={() => {
              setEditing(null);
              onChanged();
            }}
          />
        </FinanceDrawer>
      )}
    </section>
  );
}

function CostCenterForm({
  obraId,
  current,
  onSaved,
}: {
  obraId: string;
  current: FinanceCostCenter | null;
  onSaved: () => void;
}) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const codigo = String(form.get("codigo") ?? "").trim();
    const nome = String(form.get("nome") ?? "").trim();
    if (!codigo || !nome) {
      setError("Informe o código e o nome do centro de custo.");
      return;
    }
    setSaving(true);
    try {
      await salvarCentroCusto(obraId, {
        id: current?.id ?? crypto.randomUUID(),
        codigo,
        nome,
        descricao: String(form.get("descricao") ?? "").trim() || null,
        responsavelId: current?.responsavelId ?? null,
      });
      onSaved();
    } catch (reason: unknown) {
      setError(reason instanceof Error ? reason.message : "Não foi possível salvar o centro de custo.");
      setSaving(false);
    }
  }
  return (
    <form className="finance-form" onSubmit={(event) => void submit(event)}>
      <label>Código<input autoFocus required name="codigo" defaultValue={current?.codigo ?? ""} /></label>
      <label>Nome<input required name="nome" defaultValue={current?.nome ?? ""} /></label>
      <label>Descrição<textarea name="descricao" rows={4} defaultValue={current?.descricao ?? ""} /></label>
      {error ? <p className="finance-form-error" role="alert">{error}</p> : null}
      <footer><button type="submit" className="finance-primary-action" disabled={saving}>{saving ? "Salvando…" : "Salvar centro de custo"}</button></footer>
    </form>
  );
}
