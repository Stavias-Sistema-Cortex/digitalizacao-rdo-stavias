import { useCallback, useEffect, useState } from "react";

import {
  buscarCapacidadesFinanceiras,
  buscarCategoriasFinanceiras,
  buscarCentrosCusto,
  buscarCobrancas,
  buscarCompras,
  buscarFornecedores,
  buscarLancamentos,
  buscarNotasFiscais,
  buscarRelatorio,
  buscarStatusFinanceiros,
  buscarVisaoGeral,
} from "./financeiroApi";
import {
  listPendingFinancePurchases,
  mergeFinancePurchases,
} from "./financeiroOfflineRepository";
import type {
  FinanceCapabilities,
  FinanceCategory,
  FinanceCharge,
  FinanceCostCenter,
  FinanceFilters,
  FinanceInvoice,
  FinanceLedgerEntry,
  FinanceOverview,
  FinancePurchase,
  FinanceReportRow,
  FinanceStatusDefinition,
  FinanceSupplier,
} from "./financeiro.types";

export type FinanceSection =
  | "visao-geral"
  | "compras"
  | "notas-fiscais"
  | "pagamentos"
  | "centros-custo"
  | "relatorios";

interface FinanceWorkspaceData {
  capabilities: FinanceCapabilities | null;
  suppliers: FinanceSupplier[];
  costCenters: FinanceCostCenter[];
  categories: FinanceCategory[];
  statuses: FinanceStatusDefinition[];
  overview: FinanceOverview | null;
  purchases: FinancePurchase[];
  invoices: FinanceInvoice[];
  ledger: FinanceLedgerEntry[];
  charges: FinanceCharge[];
  report: FinanceReportRow[];
  loading: boolean;
  error: string;
  reload: () => void;
}

const EMPTY_ARRAY: never[] = [];

export function useFinanceiroData(
  filters: FinanceFilters,
  section: FinanceSection,
  reportGroup: string,
): FinanceWorkspaceData {
  const [version, setVersion] = useState(0);
  const [capabilities, setCapabilities] =
    useState<FinanceCapabilities | null>(null);
  const [suppliers, setSuppliers] = useState<FinanceSupplier[]>(EMPTY_ARRAY);
  const [costCenters, setCostCenters] = useState<FinanceCostCenter[]>(EMPTY_ARRAY);
  const [categories, setCategories] = useState<FinanceCategory[]>(EMPTY_ARRAY);
  const [statuses, setStatuses] = useState<FinanceStatusDefinition[]>(EMPTY_ARRAY);
  const [overview, setOverview] = useState<FinanceOverview | null>(null);
  const [purchases, setPurchases] = useState<FinancePurchase[]>(EMPTY_ARRAY);
  const [invoices, setInvoices] = useState<FinanceInvoice[]>(EMPTY_ARRAY);
  const [ledger, setLedger] = useState<FinanceLedgerEntry[]>(EMPTY_ARRAY);
  const [charges, setCharges] = useState<FinanceCharge[]>(EMPTY_ARRAY);
  const [report, setReport] = useState<FinanceReportRow[]>(EMPTY_ARRAY);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const reload = useCallback(() => setVersion((value) => value + 1), []);

  useEffect(() => {
    let cancelled = false;
    if (!filters.obraId) {
      return () => {
        cancelled = true;
      };
    }

    async function load() {
      setLoading(true);
      setError("");
      try {
        const grants = await buscarCapacidadesFinanceiras(filters.obraId);
        if (cancelled) return;
        setCapabilities(grants);
        if (!grants.permissoes.includes("FINANCEIRO_VISUALIZAR")) {
          setSuppliers([]);
          setCostCenters([]);
          setCategories([]);
          setStatuses([]);
          setOverview(null);
          setPurchases([]);
          setInvoices([]);
          setLedger([]);
          setCharges([]);
          setReport([]);
          return;
        }

        const [supplierRows, centerRows, categoryRows, purchaseStatuses,
          invoiceStatuses, ledgerStatuses] = await Promise.all([
          buscarFornecedores(filters.obraId),
          buscarCentrosCusto(filters.obraId),
          buscarCategoriasFinanceiras(filters.obraId),
          buscarStatusFinanceiros(filters.obraId, "COMPRA"),
          buscarStatusFinanceiros(filters.obraId, "NOTA_FISCAL"),
          buscarStatusFinanceiros(filters.obraId, "LANCAMENTO"),
        ]);
        if (cancelled) return;
        setSuppliers(supplierRows);
        setCostCenters(centerRows);
        setCategories(categoryRows);
        setStatuses([
          ...purchaseStatuses,
          ...invoiceStatuses,
          ...ledgerStatuses,
        ]);

        if (section === "visao-geral") {
          const [summary, ledgerRows] = await Promise.all([
            buscarVisaoGeral(filters),
            buscarLancamentos(filters),
          ]);
          if (!cancelled) {
            setOverview(summary);
            setLedger(ledgerRows);
          }
        }
        if (section === "compras") {
          const [serverRows, localRows] = await Promise.all([
            navigator.onLine ? buscarCompras(filters) : Promise.resolve([]),
            listPendingFinancePurchases(filters.obraId),
          ]);
          if (!cancelled) {
            setPurchases(mergeFinancePurchases(serverRows, localRows));
          }
        }
        if (section === "notas-fiscais") {
          const rows = await buscarNotasFiscais(filters);
          if (!cancelled) setInvoices(rows);
        }
        if (section === "pagamentos") {
          const [ledgerRows, chargeRows] = await Promise.all([
            buscarLancamentos(filters),
            buscarCobrancas(filters.obraId),
          ]);
          if (!cancelled) {
            setLedger(ledgerRows);
            setCharges(chargeRows);
          }
        }
        if (section === "relatorios") {
          const [summary, rows] = await Promise.all([
            buscarVisaoGeral(filters),
            buscarRelatorio(filters, reportGroup),
          ]);
          if (!cancelled) {
            setOverview(summary);
            setReport(rows);
          }
        }
      } catch (reason: unknown) {
        if (!cancelled) {
          setError(reason instanceof Error
            ? reason.message
            : "Não foi possível carregar o financeiro desta obra.");
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, [filters, section, reportGroup, version]);

  return {
    capabilities,
    suppliers,
    costCenters,
    categories,
    statuses,
    overview,
    purchases,
    invoices,
    ledger,
    charges,
    report,
    loading,
    error,
    reload,
  };
}
