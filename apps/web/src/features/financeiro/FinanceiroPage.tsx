import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";

import { CortexShell } from "../../components/shell/CortexShell";
import { listObrasLocais } from "../../lib/db/obraLocalRepository";
import { getSession, isAlfa } from "../auth/authSession";
import {
  EMPTY_FINANCE_FILTERS,
  filtersFromSearchParams,
  filtersToSearchParams,
} from "./financeiroFilters";
import type {
  FinanceCapabilities,
  FinanceControlUnit,
  FinanceControlUnitType,
  FinanceFilters,
} from "./financeiro.types";
import {
  buscarCapacidadesUnidade,
  buscarUnidadesFinanceiras,
} from "./financeiroApi";
import {
  useFinanceiroData,
  type FinanceSection,
} from "./useFinanceiroData";
import { FinanceOverviewPanel } from "./FinanceOverviewPanel";
import { FinancePurchasesPanel } from "./FinancePurchasesPanel";
import { FinanceInvoicesPanel } from "./FinanceInvoicesPanel";
import { FinancePaymentsPanel } from "./FinancePaymentsPanel";
import { FinanceCostCentersPanel } from "./FinanceCostCentersPanel";
import { FinanceReportsPanel } from "./FinanceReportsPanel";
import { FinanceAllocationsPanel } from "./FinanceAllocationsPanel";
import { FinanceGeneralScopePanel } from "./FinanceGeneralScopePanel";
import { FinanceManualFilters } from "./FinanceManualFilters";
import "./FinanceiroPage.css";

type FinanceScopeType = "GERAL" | Exclude<FinanceControlUnitType, "CORPORATIVO">;

const SECTIONS: { id: FinanceSection; label: string }[] = [
  { id: "visao-geral", label: "Visão geral" },
  { id: "compras", label: "Compras" },
  { id: "notas-fiscais", label: "Notas fiscais" },
  { id: "pagamentos", label: "Pagamentos e cobranças" },
  { id: "rateios", label: "Rateios" },
  { id: "centros-custo", label: "Centros de custo" },
  { id: "relatorios", label: "Relatórios" },
];

const SCOPES: { id: FinanceScopeType; label: string }[] = [
  { id: "GERAL", label: "Visão geral" },
  { id: "OBRA", label: "Obras" },
  { id: "ATIVO", label: "Equipamentos" },
  { id: "ADMINISTRATIVO", label: "Administrativo" },
];

function sectionFromParams(params: URLSearchParams): FinanceSection {
  const requested = params.get("secao");
  return SECTIONS.some((section) => section.id === requested)
    ? requested as FinanceSection
    : "visao-geral";
}

function scopeFromParams(params: URLSearchParams): FinanceScopeType {
  const requested = params.get("escopo");
  return SCOPES.some((scope) => scope.id === requested)
    ? requested as FinanceScopeType
    : "GERAL";
}

export function FinanceiroPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [units, setUnits] = useState<FinanceControlUnit[]>([]);
  const [unitError, setUnitError] = useState("");
  const [unitVersion, setUnitVersion] = useState(0);
  const [unitCapabilities, setUnitCapabilities] =
    useState<FinanceCapabilities | null>(null);
  const [reportGroup, setReportGroup] = useState("CENTRO_CUSTO");
  const section = sectionFromParams(searchParams);
  const scopeType = scopeFromParams(searchParams);
  const selectedUnitId = searchParams.get("unidade")?.trim() ?? "";
  const filters = useMemo(
    () => filtersFromSearchParams(searchParams),
    [searchParams],
  );
  const data = useFinanceiroData(filters, section, reportGroup);

  useEffect(() => {
    let cancelled = false;
    async function loadUnits() {
      try {
        const remote = navigator.onLine
          ? await buscarUnidadesFinanceiras()
          : [];
        if (!cancelled && remote.length > 0) {
          setUnits(remote);
          return;
        }
        const local = await listObrasLocais();
        if (!cancelled) {
          setUnits(local.map((obra) => ({
            id: obra.id,
            tipo: "OBRA",
            obraId: obra.id,
            ativoId: null,
            codigo: obra.codigoContrato || `OBRA:${obra.id}`,
            nome: obra.nome,
            status: "ATIVA",
            versao: 0,
          })));
        }
      } catch (reason: unknown) {
        try {
          const local = await listObrasLocais();
          if (!cancelled) {
            setUnits(local.map((obra) => ({
              id: obra.id,
              tipo: "OBRA",
              obraId: obra.id,
              ativoId: null,
              codigo: obra.codigoContrato || `OBRA:${obra.id}`,
              nome: obra.nome,
              status: "ATIVA",
              versao: 0,
            })));
          }
        } catch {
          if (!cancelled) {
            setUnitError(reason instanceof Error
              ? reason.message
              : "Não foi possível carregar as unidades financeiras.");
          }
        }
      }
    }
    void loadUnits();
    return () => {
      cancelled = true;
    };
  }, [unitVersion]);

  useEffect(() => {
    let cancelled = false;
    if (!selectedUnitId || filters.obraId) {
      queueMicrotask(() => {
        if (!cancelled) setUnitCapabilities(null);
      });
      return () => { cancelled = true; };
    }
    void buscarCapacidadesUnidade(selectedUnitId)
      .then((capabilities) => {
        if (!cancelled) setUnitCapabilities(capabilities);
      })
      .catch(() => {
        if (!cancelled) setUnitCapabilities(null);
      });
    return () => { cancelled = true; };
  }, [filters.obraId, selectedUnitId]);

  function applyParams(
    nextFilters: FinanceFilters,
    nextSection = section,
    nextScope = scopeType,
    nextUnitId = selectedUnitId,
  ) {
    const params = filtersToSearchParams(nextFilters);
    params.set("secao", nextSection);
    params.set("escopo", nextScope);
    if (nextUnitId) params.set("unidade", nextUnitId);
    setSearchParams(params, { replace: true });
  }

  function updateFilters(next: FinanceFilters) {
    applyParams(next);
  }

  function patchFilters(patch: Partial<FinanceFilters>) {
    updateFilters({ ...filters, ...patch });
  }

  const selectedUnit = units.find((unit) => unit.id === selectedUnitId);
  const visibleUnits = scopeType === "GERAL"
    ? units.filter((unit) => unit.tipo !== "CORPORATIVO")
    : units.filter((unit) => unit.tipo === scopeType);
  const permissions = data.capabilities?.permissoes ?? [];
  const canView = permissions.includes("FINANCEIRO_VISUALIZAR");
  const canOperateUnit = isAlfa(getSession()) ||
    Boolean(unitCapabilities?.permissoes.includes("FINANCEIRO_OPERAR"));

  function selectScope(nextScope: FinanceScopeType) {
    applyParams(
      { ...filters, obraId: "" },
      section,
      nextScope,
      "",
    );
  }

  function selectUnit(unitId: string) {
    const unit = units.find((candidate) => candidate.id === unitId);
    applyParams(
      { ...filters, obraId: unit?.obraId ?? "" },
      section,
      scopeType,
      unit?.id ?? "",
    );
  }

  return (
    <CortexShell
      active="financeiro"
      onRefresh={data.reload}
      isRefreshing={data.loading}
    >
      <main className="finance-page">
        <header className="finance-page-header">
          <div>
            <p className="finance-kicker">Operação</p>
            <h1>Financeiro</h1>
            <p>
              Compras, notas fiscais, rateios, pagamentos e cobranças da operação.
            </p>
          </div>
        </header>

        {unitError ? (
          <div className="finance-error-state" role="alert">{unitError}</div>
        ) : null}

        <section className="finance-scope-bar" aria-label="Escopo financeiro">
          <nav>
            {SCOPES.map((scope) => (
              <button
                key={scope.id}
                type="button"
                className={scopeType === scope.id ? "is-active" : ""}
                onClick={() => selectScope(scope.id)}
              >
                {scope.label}
              </button>
            ))}
          </nav>
          {scopeType !== "GERAL" ? (
            <label>
              Unidade em análise
              <select value={selectedUnitId} onChange={(event) => selectUnit(event.target.value)}>
                <option value="">Consolidado autorizado</option>
                {visibleUnits.map((unit) => (
                  <option key={unit.id} value={unit.id}>
                    {unit.codigo} · {unit.nome}
                  </option>
                ))}
              </select>
            </label>
          ) : (
            <p>{units.length} unidades autorizadas no consolidado</p>
          )}
        </section>

        <nav className="finance-section-nav" aria-label="Áreas do financeiro">
          {SECTIONS.map((item) => (
            <button
              key={item.id}
              type="button"
              className={section === item.id ? "is-active" : ""}
              onClick={() => applyParams(filters, item.id)}
            >
              {item.label}
            </button>
          ))}
        </nav>

        {filters.obraId && (
          <>
          <FinanceFiltersBar
            filters={filters}
            section={section}
            suppliers={data.suppliers}
            costCenters={data.costCenters}
            categories={data.categories}
            statuses={data.statuses.filter((status) => {
              if (section === "compras") return status.agregadoTipo === "COMPRA";
              if (section === "notas-fiscais") return status.agregadoTipo === "NOTA_FISCAL";
              return status.agregadoTipo === "LANCAMENTO";
            })}
            onChange={patchFilters}
            onClear={() => updateFilters({
              ...EMPTY_FINANCE_FILTERS,
              obraId: filters.obraId,
              moeda: filters.moeda,
            })}
          />
          {!["visao-geral", "centros-custo", "relatorios"].includes(section) ? (
            <FinanceManualFilters
              mode={filters.manualMode}
              rules={filters.manualRules}
              onModeChange={(manualMode) => patchFilters({ manualMode })}
              onRulesChange={(manualRules) => patchFilters({ manualRules })}
            />
          ) : null}
          </>
        )}

        {!filters.obraId && section === "visao-geral" ? (
          <FinanceGeneralScopePanel
            units={visibleUnits}
            selectedUnitId={selectedUnitId}
            canAdminister={isAlfa(getSession())}
            onSelect={(unit) => {
              const nextScope = unit.tipo === "CORPORATIVO" ? "GERAL" : unit.tipo;
              applyParams(
                { ...filters, obraId: unit.obraId ?? "" },
                section,
                nextScope,
                unit.id,
              );
            }}
            onChanged={() => setUnitVersion((value) => value + 1)}
          />
        ) : !filters.obraId && section === "rateios" ? (
          <FinanceAllocationsPanel
            units={units}
            selectedUnitId={selectedUnitId}
            purchases={[]}
            invoices={[]}
            ledger={[]}
            canOperate={canOperateUnit}
            onChanged={data.reload}
          />
        ) : !filters.obraId ? (
          <section className="finance-empty">
            <div className="finance-empty-mark" aria-hidden="true">↳</div>
            <div>
              <h2>Escolha uma obra para esta área</h2>
              <p>
                Compras, notas e pagamentos continuam vinculados à origem real.
                Use Rateios para consultar equipamentos e unidades administrativas.
              </p>
            </div>
          </section>
        ) : data.loading && !data.capabilities ? (
          <div className="finance-loading" role="status">Carregando o escopo financeiro…</div>
        ) : data.capabilities && !canView ? (
          <section className="finance-denied" role="status">
            <span aria-hidden="true">⊘</span>
            <div>
              <h2>Acesso financeiro não concedido</h2>
              <p>
                Você pode acessar {selectedUnit?.nome || "esta obra"}, mas
                não possui a capacidade FINANCEIRO_VISUALIZAR para os dados financeiros.
              </p>
            </div>
          </section>
        ) : (
          <>
            {data.error ? (
              <div className="finance-error-state" role="alert">
                <div><strong>Não foi possível atualizar esta área.</strong><span>{data.error}</span></div>
                <button type="button" onClick={data.reload}>Tentar novamente</button>
              </div>
            ) : null}
            {data.loading ? <div className="finance-progress" role="status"><span />Atualizando dados reais…</div> : null}
            {canView && section === "visao-geral" && <FinanceOverviewPanel overview={data.overview} ledger={data.ledger} />}
            {canView && section === "compras" && (
              <FinancePurchasesPanel
                obraId={filters.obraId}
                purchases={data.purchases}
                suppliers={data.suppliers}
                costCenters={data.costCenters}
                categories={data.categories}
                statuses={data.statuses}
                permissions={permissions}
                onChanged={data.reload}
              />
            )}
            {canView && section === "notas-fiscais" && (
              <FinanceInvoicesPanel
                obraId={filters.obraId}
                invoices={data.invoices}
                suppliers={data.suppliers}
                costCenters={data.costCenters}
                categories={data.categories}
                statuses={data.statuses}
                permissions={permissions}
                onChanged={data.reload}
              />
            )}
            {canView && section === "pagamentos" && (
              <FinancePaymentsPanel
                obraId={filters.obraId}
                ledger={data.ledger}
                charges={data.charges}
                suppliers={data.suppliers}
                costCenters={data.costCenters}
                categories={data.categories}
                statuses={data.statuses}
                permissions={permissions}
                onChanged={data.reload}
              />
            )}
            {canView && section === "rateios" && (
              <FinanceAllocationsPanel
                units={units}
                selectedUnitId={selectedUnitId}
                purchases={data.purchases}
                invoices={data.invoices}
                ledger={data.ledger}
                canOperate={permissions.includes("FINANCEIRO_OPERAR")}
                onChanged={data.reload}
              />
            )}
            {canView && section === "centros-custo" && (
              <FinanceCostCentersPanel
                obraId={filters.obraId}
                costCenters={data.costCenters}
                permissions={permissions}
                onChanged={data.reload}
              />
            )}
            {canView && section === "relatorios" && (
              <FinanceReportsPanel
                filters={filters}
                overview={data.overview}
                rows={data.report}
                reportGroup={reportGroup}
                onReportGroupChange={setReportGroup}
              />
            )}
          </>
        )}
      </main>
    </CortexShell>
  );
}

function FinanceFiltersBar({
  filters,
  section,
  suppliers,
  costCenters,
  categories,
  statuses,
  onChange,
  onClear,
}: {
  filters: FinanceFilters;
  section: FinanceSection;
  suppliers: { id: string; nomeFantasia: string | null; razaoSocial: string }[];
  costCenters: { id: string; codigo: string; nome: string }[];
  categories: { id: string; nome: string }[];
  statuses: { id: string; nome: string }[];
  onChange: (patch: Partial<FinanceFilters>) => void;
  onClear: () => void;
}) {
  return (
    <form
      className="finance-filter-bar"
      aria-label="Filtros financeiros"
      onSubmit={(event) => {
        event.preventDefault();
        const form = new FormData(event.currentTarget);
        onChange({ query: String(form.get("query") ?? "").trim() });
      }}
    >
      <label className="finance-search-field">
        <span className="sr-only">Buscar</span>
        <input key={filters.query} name="query" defaultValue={filters.query} placeholder="Buscar número, descrição ou CNPJ" />
        <button type="submit">Buscar</button>
      </label>
      <label><span>De</span><input type="date" value={filters.de} onChange={(event) => onChange({ de: event.target.value })} /></label>
      <label><span>Até</span><input type="date" value={filters.ate} onChange={(event) => onChange({ ate: event.target.value })} /></label>
      <label><span>Fornecedor</span><select value={filters.fornecedorId} onChange={(event) => onChange({ fornecedorId: event.target.value })}><option value="">Todos</option>{suppliers.map((supplier) => <option key={supplier.id} value={supplier.id}>{supplier.nomeFantasia || supplier.razaoSocial}</option>)}</select></label>
      <label><span>Centro de custo</span><select value={filters.centroCustoId} onChange={(event) => onChange({ centroCustoId: event.target.value })}><option value="">Todos</option>{costCenters.map((center) => <option key={center.id} value={center.id}>{center.codigo} · {center.nome}</option>)}</select></label>
      <label><span>Categoria</span><select value={filters.categoriaId} onChange={(event) => onChange({ categoriaId: event.target.value })}><option value="">Todas</option>{categories.map((category) => <option key={category.id} value={category.id}>{category.nome}</option>)}</select></label>
      <label><span>Status</span><select value={filters.statusId} onChange={(event) => onChange({ statusId: event.target.value })}><option value="">Todos</option>{statuses.map((status) => <option key={status.id} value={status.id}>{status.nome}</option>)}</select></label>
      {section === "compras" ? (
        <label><span>Prioridade</span><select value={filters.prioridade} onChange={(event) => onChange({ prioridade: event.target.value })}><option value="">Todas</option><option value="BAIXA">Baixa</option><option value="MEDIA">Média</option><option value="ALTA">Alta</option><option value="URGENTE">Urgente</option></select></label>
      ) : null}
      {["visao-geral", "pagamentos", "relatorios"].includes(section) ? (
        <label><span>Tipo</span><select value={filters.tipo} onChange={(event) => onChange({ tipo: event.target.value })}><option value="">Todos</option><option value="PAGAR">A pagar</option><option value="RECEBER">A receber</option></select></label>
      ) : null}
      {["visao-geral", "relatorios"].includes(section) ? (
        <label><span>Moeda</span><input value={filters.moeda} maxLength={3} onChange={(event) => onChange({ moeda: event.target.value.toUpperCase() })} /></label>
      ) : null}
      <button type="button" className="finance-clear-filters" onClick={onClear}>Limpar filtros</button>
    </form>
  );
}
