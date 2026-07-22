import { useMemo, useState } from "react";
import { Link } from "react-router-dom";

import {
  arquivarCompra,
  salvarCompra,
} from "./financeiroApi";
import {
  queueFinancePurchase,
  retryFinancePurchase,
} from "./financeiroOfflineRepository";
import { syncNow } from "../../lib/sync/syncEngine";
import type {
  FinanceCategory,
  FinanceCostCenter,
  FinancePurchase,
  FinancePurchaseDraft,
  FinanceStatusDefinition,
  FinanceSupplier,
  FinancialPermission,
} from "./financeiro.types";
import {
  formatDate,
  formatDateTime,
  formatMoney,
  hasFinancialPermission,
  statusTone,
} from "./financeiroView";
import { FinanceDrawer } from "./FinanceDrawer";
import { memoryHref } from "../home/memory/memoryLocation";

interface FinancePurchasesPanelProps {
  obraId: string;
  purchases: FinancePurchase[];
  suppliers: FinanceSupplier[];
  costCenters: FinanceCostCenter[];
  categories: FinanceCategory[];
  statuses: FinanceStatusDefinition[];
  permissions: FinancialPermission[];
  onChanged: () => void;
}

type DrawerState =
  | { type: "form"; purchase: FinancePurchase | null }
  | { type: "detail"; purchase: FinancePurchase }
  | null;

export function FinancePurchasesPanel({
  obraId,
  purchases,
  suppliers,
  costCenters,
  categories,
  statuses,
  permissions,
  onChanged,
}: FinancePurchasesPanelProps) {
  const [view, setView] = useState<"table" | "kanban">("table");
  const [drawer, setDrawer] = useState<DrawerState>(null);
  const canOperate = hasFinancialPermission(permissions, "FINANCEIRO_OPERAR");
  const purchaseStatuses = statuses.filter((status) =>
    status.agregadoTipo === "COMPRA" && status.ativo,
  );

  const groups = useMemo(() => {
    const grouped = new Map<string, FinancePurchase[]>();
    for (const purchase of purchases) {
      const key = purchase.statusNome || purchase.statusCodigo || "Sem status confirmado";
      grouped.set(key, [...(grouped.get(key) ?? []), purchase]);
    }
    return [...grouped.entries()];
  }, [purchases]);

  return (
    <section className="finance-workspace-section">
      <div className="finance-section-heading">
        <div>
          <p className="finance-kicker">Compras da obra</p>
          <h2>Pedidos e aprovações</h2>
          <p>{purchases.length} registros no filtro atual</p>
        </div>
        <div className="finance-heading-actions">
          <div className="finance-view-switch" aria-label="Visualização de compras">
            <button
              type="button"
              className={view === "table" ? "is-active" : ""}
              onClick={() => setView("table")}
            >
              Tabela
            </button>
            <button
              type="button"
              className={view === "kanban" ? "is-active" : ""}
              onClick={() => setView("kanban")}
            >
              Quadro
            </button>
          </div>
          {canOperate && (
            <button
              type="button"
              className="finance-primary-action"
              onClick={() => setDrawer({ type: "form", purchase: null })}
            >
              Nova compra
            </button>
          )}
        </div>
      </div>

      {purchases.length === 0 ? (
        <div className="finance-row-empty">
          Nenhuma compra encontrada. Ajuste os filtros ou registre a primeira compra.
        </div>
      ) : view === "table" ? (
        <div className="finance-table-wrap">
          <table className="finance-table">
            <thead>
              <tr>
                <th>Pedido</th>
                <th>Descrição</th>
                <th>Fornecedor</th>
                <th>Status</th>
                <th>Entrega</th>
                <th className="is-number">Contratado</th>
                <th><span className="sr-only">Ações</span></th>
              </tr>
            </thead>
            <tbody>
              {purchases.map((purchase) => (
                <tr key={purchase.id}>
                  <td data-label="Pedido">
                    <strong>{purchase.numeroPedido || "Sem número"}</strong>
                    {purchase.syncStatus && purchase.syncStatus !== "SYNCED" ? (
                      <span className={`finance-sync-label is-${purchase.syncStatus.toLowerCase()}`}>
                        {purchase.syncStatus === "PENDING_SYNC"
                          ? "Na fila"
                          : purchase.syncStatus === "ERROR"
                            ? "Falha ao sincronizar"
                            : "Conflito"}
                      </span>
                    ) : null}
                  </td>
                  <td data-label="Descrição">{purchase.descricao}</td>
                  <td data-label="Fornecedor">
                    {purchase.fornecedorNome || purchase.fornecedorId}
                  </td>
                  <td data-label="Status">
                    <span className={`finance-pill is-${statusTone(purchase.statusCodigo)}`}>
                      {purchase.statusNome || purchase.statusCodigo || "Aguardando servidor"}
                    </span>
                  </td>
                  <td data-label="Entrega">{formatDate(purchase.entregaPrevistaEm)}</td>
                  <td data-label="Contratado" className="is-number">
                    {formatMoney(
                      purchase.valorContratado ?? purchase.valorPrevisto,
                      purchase.moeda,
                    )}
                  </td>
                  <td className="finance-row-actions">
                    <button
                      type="button"
                      onClick={() => setDrawer({ type: "detail", purchase })}
                    >
                      Detalhes
                    </button>
                    {canOperate && !purchase.syncStatus && (
                      <button
                        type="button"
                        onClick={() => setDrawer({ type: "form", purchase })}
                      >
                        Editar
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="finance-kanban" aria-label="Compras por status">
          {groups.map(([status, rows]) => (
            <section key={status}>
              <header>
                <h3>{status}</h3>
                <span>{rows.length}</span>
              </header>
              <div>
                {rows.map((purchase) => (
                  <button
                    type="button"
                    className="finance-kanban-card"
                    key={purchase.id}
                    onClick={() => setDrawer({ type: "detail", purchase })}
                  >
                    <span>{purchase.numeroPedido || "Sem número"}</span>
                    <strong>{purchase.descricao}</strong>
                    <small>{purchase.fornecedorNome || purchase.fornecedorId}</small>
                    <b>{formatMoney(
                      purchase.valorContratado ?? purchase.valorPrevisto,
                      purchase.moeda,
                    )}</b>
                  </button>
                ))}
              </div>
            </section>
          ))}
        </div>
      )}

      {drawer?.type === "form" && (
        <FinanceDrawer
          title={drawer.purchase ? "Editar compra" : "Registrar compra"}
          description={navigator.onLine
            ? "A alteração será gravada no servidor com rastreabilidade."
            : "Sem conexão: a nova compra ficará protegida neste dispositivo e entrará na fila de sincronização."}
          onClose={() => setDrawer(null)}
        >
          <PurchaseForm
            obraId={obraId}
            purchase={drawer.purchase}
            suppliers={suppliers}
            costCenters={costCenters}
            categories={categories}
            statuses={purchaseStatuses}
            onSaved={() => {
              setDrawer(null);
              onChanged();
            }}
          />
        </FinanceDrawer>
      )}

      {drawer?.type === "detail" && (
        <PurchaseDetail
          purchase={drawer.purchase}
          canArchive={canOperate && !drawer.purchase.syncStatus}
          onClose={() => setDrawer(null)}
          onArchived={() => {
            setDrawer(null);
            onChanged();
          }}
        />
      )}
    </section>
  );
}

interface PurchaseFormProps {
  obraId: string;
  purchase: FinancePurchase | null;
  suppliers: FinanceSupplier[];
  costCenters: FinanceCostCenter[];
  categories: FinanceCategory[];
  statuses: FinanceStatusDefinition[];
  onSaved: () => void;
}

function PurchaseForm({
  obraId,
  purchase,
  suppliers,
  costCenters,
  categories,
  statuses,
  onSaved,
}: PurchaseFormProps) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const draft: FinancePurchaseDraft = {
      obraId,
      solicitacaoId: purchase?.solicitacaoId ?? null,
      fornecedorId: String(form.get("fornecedorId") || ""),
      centroCustoId: String(form.get("centroCustoId") || ""),
      categoriaId: String(form.get("categoriaId") || ""),
      responsavelCompraId: purchase?.responsavelCompraId ?? null,
      statusId: String(form.get("statusId") || ""),
      numeroPedido: String(form.get("numeroPedido") || "").trim(),
      descricao: String(form.get("descricao") || "").trim(),
      prioridade: String(form.get("prioridade") || "MEDIA"),
      moeda: String(form.get("moeda") || "BRL").trim().toUpperCase(),
      valorPrevisto: Number(form.get("valorPrevisto")),
      valorAprovado: numberOrNull(form.get("valorAprovado")),
      valorContratado: numberOrNull(form.get("valorContratado")),
      valorPago: purchase?.valorPago ?? null,
      pedidoEmitidoEm: textOrNull(form.get("pedidoEmitidoEm")),
      entregaPrevistaEm: textOrNull(form.get("entregaPrevistaEm")),
      itens: purchase?.itens ?? [],
    };
    if (!draft.fornecedorId || !draft.centroCustoId || !draft.categoriaId ||
      !draft.statusId || !draft.descricao || !Number.isFinite(draft.valorPrevisto)) {
      setError("Preencha fornecedor, centro de custo, categoria, status, descrição e valor previsto.");
      return;
    }
    setSaving(true);
    setError("");
    try {
      if (!navigator.onLine && !purchase) {
        await queueFinancePurchase(draft);
      } else if (!navigator.onLine) {
        throw new Error("Edições exigem conexão para validar a versão atual da compra.");
      } else {
        await salvarCompra(draft, purchase ?? undefined);
      }
      onSaved();
    } catch (reason: unknown) {
      setError(reason instanceof Error ? reason.message : "Não foi possível salvar a compra.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className="finance-form" onSubmit={(event) => void submit(event)}>
      <div className="finance-form-grid">
        <label>
          Pedido
          <input name="numeroPedido" defaultValue={purchase?.numeroPedido ?? ""} maxLength={80} />
        </label>
        <label>
          Prioridade
          <select name="prioridade" defaultValue={purchase?.prioridade ?? "MEDIA"}>
            <option value="BAIXA">Baixa</option>
            <option value="MEDIA">Média</option>
            <option value="ALTA">Alta</option>
            <option value="URGENTE">Urgente</option>
          </select>
        </label>
        <label className="is-wide">
          Descrição
          <textarea name="descricao" required defaultValue={purchase?.descricao ?? ""} rows={3} />
        </label>
        <label>
          Fornecedor
          <select name="fornecedorId" required defaultValue={purchase?.fornecedorId ?? ""}>
            <option value="">Selecione</option>
            {suppliers.map((supplier) => (
              <option key={supplier.id} value={supplier.id}>
                {supplier.nomeFantasia || supplier.razaoSocial}
              </option>
            ))}
          </select>
        </label>
        <label>
          Centro de custo
          <select name="centroCustoId" required defaultValue={purchase?.centroCustoId ?? ""}>
            <option value="">Selecione</option>
            {costCenters.map((center) => (
              <option key={center.id} value={center.id}>{center.codigo} · {center.nome}</option>
            ))}
          </select>
        </label>
        <label>
          Categoria
          <select name="categoriaId" required defaultValue={purchase?.categoriaId ?? ""}>
            <option value="">Selecione</option>
            {categories.map((category) => (
              <option key={category.id} value={category.id}>{category.nome}</option>
            ))}
          </select>
        </label>
        <label>
          Status
          <select name="statusId" required defaultValue={purchase?.statusId ?? statuses[0]?.id ?? ""}>
            <option value="">Selecione</option>
            {statuses.map((status) => (
              <option key={status.id} value={status.id}>{status.nome}</option>
            ))}
          </select>
        </label>
        <label>
          Moeda
          <input name="moeda" required maxLength={3} defaultValue={purchase?.moeda ?? "BRL"} />
        </label>
        <label>
          Valor previsto
          <input name="valorPrevisto" required type="number" min="0" step="0.01" defaultValue={purchase?.valorPrevisto ?? ""} />
        </label>
        <label>
          Valor aprovado
          <input name="valorAprovado" type="number" min="0" step="0.01" defaultValue={purchase?.valorAprovado ?? ""} />
        </label>
        <label>
          Valor contratado
          <input name="valorContratado" type="number" min="0" step="0.01" defaultValue={purchase?.valorContratado ?? ""} />
        </label>
        <label>
          Pedido emitido em
          <input name="pedidoEmitidoEm" type="date" defaultValue={purchase?.pedidoEmitidoEm ?? ""} />
        </label>
        <label>
          Entrega prevista
          <input name="entregaPrevistaEm" type="date" defaultValue={purchase?.entregaPrevistaEm ?? ""} />
        </label>
      </div>
      {error ? <p className="finance-form-error" role="alert">{error}</p> : null}
      <footer>
        <button className="finance-primary-action" type="submit" disabled={saving}>
          {saving ? "Salvando…" : navigator.onLine ? "Salvar compra" : "Salvar na fila"}
        </button>
      </footer>
    </form>
  );
}

function PurchaseDetail({
  purchase,
  canArchive,
  onClose,
  onArchived,
}: {
  purchase: FinancePurchase;
  canArchive: boolean;
  onClose: () => void;
  onArchived: () => void;
}) {
  const [error, setError] = useState("");
  const [archiving, setArchiving] = useState(false);
  const [retrying, setRetrying] = useState(false);

  async function archive() {
    if (!window.confirm("Arquivar esta compra? O histórico será preservado.")) return;
    setArchiving(true);
    try {
      await arquivarCompra(purchase);
      onArchived();
    } catch (reason: unknown) {
      setError(reason instanceof Error ? reason.message : "Não foi possível arquivar a compra.");
      setArchiving(false);
    }
  }

  async function retrySync() {
    if (!purchase.clientMutationId) return;
    setRetrying(true);
    setError("");
    try {
      await retryFinancePurchase(purchase.clientMutationId);
      if (navigator.onLine) {
        await syncNow();
      }
      onArchived();
    } catch (reason: unknown) {
      setError(reason instanceof Error ? reason.message : "Não foi possível reenviar a compra.");
      setRetrying(false);
    }
  }

  return (
    <FinanceDrawer title={purchase.numeroPedido || "Detalhes da compra"} onClose={onClose}>
      <dl className="finance-detail-list">
        <div><dt>Descrição</dt><dd>{purchase.descricao}</dd></div>
        <div><dt>Fornecedor</dt><dd>{purchase.fornecedorNome || purchase.fornecedorId}</dd></div>
        <div><dt>Centro de custo</dt><dd>{purchase.centroCustoNome || purchase.centroCustoId}</dd></div>
        <div><dt>Valor previsto</dt><dd>{formatMoney(purchase.valorPrevisto, purchase.moeda)}</dd></div>
        <div><dt>Entrega</dt><dd>{formatDate(purchase.entregaPrevistaEm)}</dd></div>
        <div><dt>Atualização</dt><dd>{formatDateTime(purchase.atualizadoEm)}</dd></div>
      </dl>
      {purchase.syncStatus ? (
        <div className="finance-inline-note">
          <p>Este registro ainda está somente no dispositivo. A rastreabilidade do servidor aparecerá após a sincronização.</p>
          {purchase.syncError ? <p>{purchase.syncError}</p> : null}
          {purchase.syncStatus === "ERROR" && purchase.clientMutationId ? (
            <button type="button" onClick={() => void retrySync()} disabled={retrying}>
              {retrying ? "Reenviando…" : navigator.onLine ? "Tentar sincronizar agora" : "Colocar novamente na fila"}
            </button>
          ) : null}
        </div>
      ) : null}
      <section className="finance-audit finance-memory-reference">
        <div>
          <h3>Memória operacional</h3>
          <p>Criação, alterações e arquivamento desta compra ficam no registro ontológico central.</p>
        </div>
        <Link to={memoryHref({
          obraId: purchase.obraId,
          entityType: "COMPRA",
          entityId: purchase.id,
        })}>
          Ver na Memória
        </Link>
      </section>
      {error ? <p className="finance-form-error" role="alert">{error}</p> : null}
      {canArchive && (
        <button
          type="button"
          className="finance-danger-action"
          onClick={() => void archive()}
          disabled={archiving}
        >
          {archiving ? "Arquivando…" : "Arquivar compra"}
        </button>
      )}
    </FinanceDrawer>
  );
}

function numberOrNull(value: FormDataEntryValue | null): number | null {
  if (value === null || String(value).trim() === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function textOrNull(value: FormDataEntryValue | null): string | null {
  const text = String(value ?? "").trim();
  return text || null;
}
