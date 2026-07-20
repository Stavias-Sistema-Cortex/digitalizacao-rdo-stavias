import { useEffect, useMemo, useState } from "react";

import {
  buscarHistoricoRateio,
  buscarRateioOrigem,
  buscarRateios,
  salvarRateio,
} from "./financeiroApi";
import type {
  FinanceAllocation,
  FinanceAllocationHistoryEntry,
  FinanceAllocationSourceType,
  FinanceControlUnit,
  FinanceInvoice,
  FinanceLedgerEntry,
  FinancePurchase,
} from "./financeiro.types";
import { formatDateTime, formatMoney } from "./financeiroView";
import { FinanceDrawer } from "./FinanceDrawer";

interface AllocationSource {
  id: string;
  type: FinanceAllocationSourceType;
  title: string;
  detail: string;
  value: number;
  currency: string;
}

interface DraftItem {
  id: string;
  unitId: string;
  value: string;
}

export function FinanceAllocationsPanel({
  units,
  selectedUnitId,
  purchases,
  invoices,
  ledger,
  canOperate,
  onChanged,
}: {
  units: FinanceControlUnit[];
  selectedUnitId: string;
  purchases: FinancePurchase[];
  invoices: FinanceInvoice[];
  ledger: FinanceLedgerEntry[];
  canOperate: boolean;
  onChanged: () => void;
}) {
  const [allocations, setAllocations] = useState<FinanceAllocation[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [drawer, setDrawer] = useState<{
    source: AllocationSource;
    allocation: FinanceAllocation | null;
  } | null>(null);
  const sources = useMemo(
    () => sourceOptions(purchases, invoices, ledger),
    [purchases, invoices, ledger],
  );

  async function load() {
    setLoading(true);
    setError("");
    try {
      setAllocations(await buscarRateios(selectedUnitId || undefined));
    } catch (reason: unknown) {
      setError(reason instanceof Error
        ? reason.message
        : "Não foi possível carregar os rateios.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    let cancelled = false;
    void buscarRateios(selectedUnitId || undefined)
      .then((rows) => {
        if (!cancelled) {
          setAllocations(rows);
          setError("");
        }
      })
      .catch((reason: unknown) => {
        if (!cancelled) {
          setError(reason instanceof Error
            ? reason.message
            : "Não foi possível carregar os rateios.");
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedUnitId]);

  async function chooseSource(source: AllocationSource) {
    setError("");
    try {
      setDrawer({
        source,
        allocation: await buscarRateioOrigem(source.type, source.id),
      });
    } catch (reason: unknown) {
      setError(reason instanceof Error
        ? reason.message
        : "Não foi possível abrir o rateio.");
    }
  }

  function editAllocation(allocation: FinanceAllocation) {
    const known = sources.find((source) =>
      source.id === allocation.origemId && source.type === allocation.origemTipo,
    );
    setDrawer({
      allocation,
      source: known ?? {
        id: allocation.origemId,
        type: allocation.origemTipo,
        title: `${sourceTypeLabel(allocation.origemTipo)} ${shortId(allocation.origemId)}`,
        detail: "Origem financeira persistida",
        value: allocation.valorTotal,
        currency: allocation.moeda,
      },
    });
  }

  return (
    <section className="finance-workspace-section finance-allocations">
      <div className="finance-section-heading">
        <div>
          <p className="finance-kicker">Distribuição econômica</p>
          <h2>Rateios por unidade</h2>
          <p>
            O valor fecha exatamente com a origem; equipamentos e despesas
            administrativas mantêm controle individual e histórico.
          </p>
        </div>
        {canOperate && sources.length > 0 ? (
          <label className="finance-inline-select">
            Criar ou revisar rateio
            <select
              value=""
              onChange={(event) => {
                const source = sources.find((item) =>
                  `${item.type}:${item.id}` === event.target.value,
                );
                if (source) void chooseSource(source);
              }}
            >
              <option value="">Selecionar origem</option>
              {sources.map((source) => (
                <option key={`${source.type}:${source.id}`} value={`${source.type}:${source.id}`}>
                  {source.title} · {formatMoney(source.value, source.currency)}
                </option>
              ))}
            </select>
          </label>
        ) : null}
      </div>

      {error ? <p className="finance-form-error" role="alert">{error}</p> : null}
      {loading ? (
        <p className="finance-row-empty">Carregando rateios autorizados…</p>
      ) : allocations.length === 0 ? (
        <p className="finance-row-empty">
          Nenhum rateio foi registrado neste escopo. Selecione uma origem para
          distribuir o primeiro valor.
        </p>
      ) : (
        <div className="finance-table-wrap">
          <table className="finance-table">
            <thead>
              <tr>
                <th>Origem</th>
                <th>Destinos</th>
                <th>Última alteração</th>
                <th className="is-number">Total</th>
                <th><span className="sr-only">Ações</span></th>
              </tr>
            </thead>
            <tbody>
              {allocations.map((allocation) => (
                <tr key={allocation.id}>
                  <td data-label="Origem">
                    <strong>{sourceTypeLabel(allocation.origemTipo)}</strong>
                    <span>{shortId(allocation.origemId)}</span>
                  </td>
                  <td data-label="Destinos">
                    {allocation.itens.map((item) =>
                      units.find((unit) => unit.id === item.unidadeControleId)?.nome ??
                        shortId(item.unidadeControleId),
                    ).join(" · ")}
                  </td>
                  <td data-label="Última alteração">{formatDateTime(allocation.atualizadoEm)}</td>
                  <td data-label="Total" className="is-number">
                    {formatMoney(allocation.valorTotal, allocation.moeda)}
                  </td>
                  <td className="finance-row-actions">
                    <button type="button" onClick={() => editAllocation(allocation)}>
                      {canOperate ? "Revisar" : "Histórico"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {drawer ? (
        <AllocationEditor
          source={drawer.source}
          current={drawer.allocation}
          units={units}
          selectedUnitId={selectedUnitId}
          canOperate={canOperate}
          onClose={() => setDrawer(null)}
          onSaved={() => {
            setDrawer(null);
            void load();
            onChanged();
          }}
        />
      ) : null}
    </section>
  );
}

function AllocationEditor({
  source,
  current,
  units,
  selectedUnitId,
  canOperate,
  onClose,
  onSaved,
}: {
  source: AllocationSource;
  current: FinanceAllocation | null;
  units: FinanceControlUnit[];
  selectedUnitId: string;
  canOperate: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const availableUnits = units.filter((unit) =>
    unit.status === "ATIVA" && unit.tipo !== "CORPORATIVO",
  );
  const [items, setItems] = useState<DraftItem[]>(() => current?.itens.map((item) => ({
    id: item.id,
    unitId: item.unidadeControleId,
    value: item.valor.toFixed(4),
  })) ?? [{
    id: crypto.randomUUID(),
    unitId: selectedUnitId || availableUnits[0]?.id || "",
    value: source.value.toFixed(4),
  }]);
  const [history, setHistory] = useState<FinanceAllocationHistoryEntry[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const total = moneyUnits(source.value.toFixed(4));
  const allocated = items.reduce((sum, item) => sum + moneyUnits(item.value), 0);
  const difference = total - allocated;

  useEffect(() => {
    if (!current) return;
    void buscarHistoricoRateio(current.id)
      .then((response) => setHistory(response.eventos))
      .catch((reason: unknown) => setError(reason instanceof Error
        ? reason.message
        : "Não foi possível carregar o histórico."));
  }, [current]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (difference !== 0 || items.some((item) => !item.unitId || moneyUnits(item.value) <= 0)) {
      setError("O rateio precisa usar destinos válidos e fechar exatamente com o total da origem.");
      return;
    }
    if (new Set(items.map((item) => item.unitId)).size !== items.length) {
      setError("Cada unidade pode aparecer uma vez neste rateio sem classificação adicional.");
      return;
    }
    setSaving(true);
    setError("");
    try {
      await salvarRateio(source.type, source.id, items.map((item) => ({
        unidadeControleId: item.unitId,
        centroCustoId: null,
        categoriaId: null,
        valor: moneyUnits(item.value) / 10_000,
      })), current);
      onSaved();
    } catch (reason: unknown) {
      setError(reason instanceof Error
        ? reason.message
        : "Não foi possível salvar o rateio.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <FinanceDrawer
      title="Rateio financeiro"
      description={`${source.title} · ${formatMoney(source.value, source.currency)}`}
      onClose={onClose}
      wide
    >
      <form className="finance-form finance-allocation-form" onSubmit={submit}>
        <div className="finance-allocation-balance">
          <div><span>Origem</span><strong>{formatMoney(source.value, source.currency)}</strong></div>
          <div><span>Distribuído</span><strong>{formatMoney(allocated / 10_000, source.currency)}</strong></div>
          <div className={difference === 0 ? "is-closed" : "is-open"}>
            <span>Diferença</span>
            <strong>{formatMoney(difference / 10_000, source.currency)}</strong>
          </div>
        </div>

        <div className="finance-allocation-items">
          {items.map((item, index) => (
            <div key={item.id}>
              <span>{index + 1}</span>
              <label>
                Unidade de controle
                <select
                  value={item.unitId}
                  disabled={!canOperate}
                  onChange={(event) => setItems(items.map((candidate) =>
                    candidate.id === item.id
                      ? { ...candidate, unitId: event.target.value }
                      : candidate,
                  ))}
                >
                  <option value="">Selecione</option>
                  {availableUnits.map((unit) => (
                    <option key={unit.id} value={unit.id}>
                      {unit.tipo === "ATIVO" ? "Equipamento" :
                        unit.tipo === "OBRA" ? "Obra" : "Administrativo"} · {unit.nome}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Valor
                <input
                  type="number"
                  min="0.0001"
                  step="0.0001"
                  value={item.value}
                  disabled={!canOperate}
                  onChange={(event) => setItems(items.map((candidate) =>
                    candidate.id === item.id
                      ? { ...candidate, value: event.target.value }
                      : candidate,
                  ))}
                />
              </label>
              {canOperate && items.length > 1 ? (
                <button
                  type="button"
                  onClick={() => setItems(items.filter((candidate) => candidate.id !== item.id))}
                >
                  Remover
                </button>
              ) : null}
            </div>
          ))}
        </div>

        {canOperate ? (
          <button
            type="button"
            className="finance-secondary-action"
            onClick={() => setItems([...items, {
              id: crypto.randomUUID(),
              unitId: "",
              value: difference > 0 ? (difference / 10_000).toFixed(4) : "",
            }])}
          >
            Adicionar destino
          </button>
        ) : null}

        {error ? <p className="finance-form-error" role="alert">{error}</p> : null}

        {history.length > 0 ? (
          <section className="finance-audit">
            <h3>Histórico de controle</h3>
            <ol>
              {history.map((entry) => (
                <li key={entry.id}>
                  <strong>{entry.operacao} · versão {entry.versaoNova}</strong>
                  <span>{formatDateTime(entry.alteradoEm)} · ator {shortId(entry.alteradoPor)}</span>
                </li>
              ))}
            </ol>
          </section>
        ) : null}

        <footer>
          <button type="button" className="finance-secondary-action" onClick={onClose}>Fechar</button>
          {canOperate ? (
            <button type="submit" className="finance-primary-action" disabled={saving || difference !== 0}>
              {saving ? "Salvando…" : "Salvar rateio"}
            </button>
          ) : null}
        </footer>
      </form>
    </FinanceDrawer>
  );
}

function sourceOptions(
  purchases: FinancePurchase[],
  invoices: FinanceInvoice[],
  ledger: FinanceLedgerEntry[],
): AllocationSource[] {
  return [
    ...purchases.map((row) => ({
      id: row.id,
      type: "COMPRA" as const,
      title: row.numeroPedido || "Compra sem número",
      detail: row.descricao,
      value: row.valorContratado ?? row.valorPrevisto,
      currency: row.moeda,
    })),
    ...invoices.map((row) => ({
      id: row.id,
      type: "NOTA_FISCAL" as const,
      title: `Nota ${row.numero}`,
      detail: row.fornecedorNome ?? row.tipoDocumento,
      value: row.valorLiquido,
      currency: row.moeda,
    })),
    ...ledger.map((row) => ({
      id: row.id,
      type: "LANCAMENTO" as const,
      title: row.numeroDocumento || "Lançamento manual",
      detail: row.descricao,
      value: row.valorOriginal,
      currency: row.moeda,
    })),
  ].filter((source) => source.value > 0);
}

function moneyUnits(value: string): number {
  const normalized = value.trim().replace(",", ".");
  if (!/^\d+(\.\d{0,4})?$/.test(normalized)) return Number.NaN;
  const [whole, decimal = ""] = normalized.split(".");
  return Number(whole) * 10_000 + Number(decimal.padEnd(4, "0"));
}

function sourceTypeLabel(type: FinanceAllocationSourceType): string {
  if (type === "NOTA_FISCAL") return "Nota fiscal";
  if (type === "LANCAMENTO") return "Lançamento";
  return "Compra";
}

function shortId(value: string): string {
  return value.length > 12 ? `${value.slice(0, 8)}…` : value;
}
