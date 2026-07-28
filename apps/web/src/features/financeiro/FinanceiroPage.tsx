import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router";

import { CortexShell } from "../../components/shell/CortexShell";
import { OperationalWorkspace } from "../../components/workspace/OperationalWorkspace";
import {
  listObrasLocais,
} from "../../lib/db/obraLocalRepository";
import { listOutboxMutations } from "../../lib/db/outboxRepository";
import { filterOperationalObras } from "../../lib/db/obraSelectors";
import type { OutboxMutationRecord } from "../../lib/db/db.types";
import { effectiveObraMutations } from "../../lib/sync/syncStorage";
import {
  filtersFromSearchParams,
  filtersToSearchParams,
} from "./financeiroFilters";
import type {
  FinanceCapabilities,
  FinanceFilters,
} from "./financeiro.types";
import { FinanceRevenueTracePage } from "./FinanceRevenueTracePage";
import { ServicePriceCatalogPage } from "./ServicePriceCatalogPage";
import { FinanceSectionIndex } from "./FinanceSectionIndex";
import {
  FINANCE_SECTIONS,
  type ActiveFinanceSection,
} from "./financeSectionIndexModel";
import { resolveFinanceCapabilities } from "./financeCapabilitiesResolver";
import {
  fetchAuthorizedRevenueWorksites,
  type RevenueWorksite,
} from "./financeRevenueWorksiteApi";
import { FinancePdorSection } from "./FinancePdorSection";
import "./FinanceiroPage.css";

const DEFAULT_SECTION: ActiveFinanceSection = "receita";
const PENDING_ARCHIVE_STATUSES = new Set<
  OutboxMutationRecord["status"]
>([
  "PENDING",
  "SYNCING",
]);

function sectionFromParams(params: URLSearchParams): ActiveFinanceSection {
  const requested = params.get("secao");
  return FINANCE_SECTIONS.some((section) => section.id === requested)
    ? requested as ActiveFinanceSection
    : DEFAULT_SECTION;
}

function localWorksiteUnit(
  obra: Awaited<ReturnType<typeof listObrasLocais>>[number],
): RevenueWorksite {
  return {
    id: obra.id,
    codigoContrato: obra.codigoContrato,
    nome: obra.nome,
    status: obra.status,
  };
}

function pendingArchiveMutationIds(
  mutations: OutboxMutationRecord[],
): Set<string> {
  return new Set(
    effectiveObraMutations(
      mutations.filter(
        (mutation) => mutation.entidadeTipo === "OBRA",
      ),
    )
      .filter(
        (mutation) =>
          mutation.operacao === "ARQUIVAR_OBRA" &&
          PENDING_ARCHIVE_STATUSES.has(mutation.status),
      )
      .map((mutation) => mutation.entidadeId),
  );
}

export function FinanceiroPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [worksites, setWorksites] = useState<RevenueWorksite[]>([]);
  const [worksiteError, setWorksiteError] = useState("");
  const [accessVersion, setAccessVersion] = useState(0);
  const [
    loadedWorksitesVersion,
    setLoadedWorksitesVersion,
  ] = useState<number | null>(null);
  const [
    failedWorksitesVersion,
    setFailedWorksitesVersion,
  ] = useState<number | null>(null);
  const [accessState, setAccessState] = useState<{
    obraId: string;
    capabilities: FinanceCapabilities | null;
    loading: boolean;
    error: string;
  }>({
    obraId: "",
    capabilities: null,
    loading: false,
    error: "",
  });
  const section = sectionFromParams(searchParams);
  const filters = useMemo(
    () => filtersFromSearchParams(searchParams),
    [searchParams],
  );
  const worksitesLoaded =
    loadedWorksitesVersion === accessVersion;
  const worksitesFailed =
    failedWorksitesVersion === accessVersion;
  const selectedWorksite = worksites.find(
    (unit) => unit.id === filters.obraId,
  );

  useEffect(() => {
    let cancelled = false;

    async function loadWorksites() {
      setWorksiteError("");
      let catalogResolved = false;
      try {
        if (navigator.onLine) {
          try {
            const remote = await fetchAuthorizedRevenueWorksites();
            let locallyArchivedIds = new Set<string>();
            try {
              const [cached, outbox] = await Promise.all([
                listObrasLocais({
                  includeArchived: true,
                }),
                listOutboxMutations(),
              ]);
              const pendingArchiveIds =
                pendingArchiveMutationIds(outbox);
              locallyArchivedIds = new Set(
                cached
                  .filter(
                    (obra) =>
                      obra.arquivadoEm != null &&
                      pendingArchiveIds.has(obra.id),
                  )
                  .map((obra) => obra.id),
              );
            } catch {
              // O catálogo remoto continua autoritativo quando cache e
              // outbox não comprovam juntos um arquivamento pendente.
            }
            if (!cancelled) {
              setWorksites(
                remote.filter(
                  (obra) => !locallyArchivedIds.has(obra.id),
                ),
              );
            }
            catalogResolved = true;
            return;
          } catch (reason: unknown) {
            if (!cancelled) {
              setWorksiteError(reason instanceof Error
                ? reason.message
                : "Não foi possível atualizar a lista de obras autorizadas.");
            }
          }
        }

        try {
          const local = await listObrasLocais();
          if (!cancelled) {
            setWorksites(
              filterOperationalObras(local).map(localWorksiteUnit),
            );
          }
          catalogResolved = true;
        } catch (reason: unknown) {
          if (!cancelled) {
            setWorksiteError(reason instanceof Error
              ? reason.message
              : "Não foi possível carregar as obras armazenadas neste dispositivo.");
          }
        }
      } finally {
        if (!cancelled) {
          if (catalogResolved) {
            setLoadedWorksitesVersion(accessVersion);
            setFailedWorksitesVersion(null);
          } else {
            setWorksites([]);
            setLoadedWorksitesVersion(null);
            setFailedWorksitesVersion(accessVersion);
          }
        }
      }
    }

    void loadWorksites();
    return () => {
      cancelled = true;
    };
  }, [accessVersion]);

  useEffect(() => {
    let cancelled = false;
    const obraId = filters.obraId;
    if (!obraId) {
      queueMicrotask(() => {
        if (!cancelled) {
          setAccessState({
            obraId: "",
            capabilities: null,
            loading: false,
            error: "",
          });
        }
      });
      return () => {
        cancelled = true;
      };
    }
    if (!worksitesLoaded) {
      return () => {
        cancelled = true;
      };
    }
    if (!selectedWorksite) {
      queueMicrotask(() => {
        if (cancelled) return;
        const params = filtersToSearchParams({
          ...filters,
          obraId: "",
        });
        params.set("secao", section);
        setSearchParams(params, { replace: true });
        setAccessState({
          obraId: "",
          capabilities: null,
          loading: false,
          error: "",
        });
      });
      return () => {
        cancelled = true;
      };
    }

    queueMicrotask(() => {
      if (cancelled) return;
      setAccessState({
        obraId,
        capabilities: null,
        loading: true,
        error: "",
      });
      void resolveFinanceCapabilities(obraId)
        .then((capabilities) => {
          if (!cancelled) {
            setAccessState({
              obraId,
              capabilities,
              loading: false,
              error: "",
            });
          }
        })
        .catch((reason: unknown) => {
          if (!cancelled) {
            setAccessState({
              obraId,
              capabilities: null,
              loading: false,
              error: reason instanceof Error
                ? reason.message
                : "Não foi possível validar o acesso desta obra.",
            });
          }
        });
    });

    return () => {
      cancelled = true;
    };
  }, [
    accessVersion,
    filters,
    section,
    selectedWorksite,
    setSearchParams,
    worksitesLoaded,
  ]);

  function applyParams(
    nextFilters: FinanceFilters,
    nextSection = section,
  ) {
    const params = filtersToSearchParams(nextFilters);
    params.set("secao", nextSection);
    setSearchParams(params, { replace: true });
  }

  function patchFilters(patch: Partial<FinanceFilters>) {
    applyParams({ ...filters, ...patch });
  }

  function selectWorksite(obraId: string) {
    applyParams({ ...filters, obraId });
  }

  const currentAccess = accessState.obraId === filters.obraId
    ? accessState
    : null;
  const permissions = currentAccess?.capabilities?.permissoes ?? [];
  const canViewSelectedWorksite = !filters.obraId ||
    permissions.includes("FINANCEIRO_VISUALIZAR");
  const selectedWorksitePending = Boolean(
    filters.obraId && (
      !worksitesLoaded ||
      !selectedWorksite ||
      currentAccess === null ||
      currentAccess.loading
    ),
  );
  function renderSelectedSection() {
    if (section === "receita") {
      return (
        <>
          <section
            className="finance-revenue-period"
            aria-label="Período da receita"
          >
            <label>
              De
              <input
                type="date"
                value={filters.de}
                onChange={(event) => patchFilters({ de: event.target.value })}
              />
            </label>
            <label>
              Até
              <input
                type="date"
                value={filters.ate}
                onChange={(event) => patchFilters({ ate: event.target.value })}
              />
            </label>
            <p>
              Somente trabalho aceito em RDO e ligado ao preço vigente entra
              na receita.
            </p>
          </section>
          <FinanceRevenueTracePage
            key={`revenue-${accessVersion}`}
            obraId={filters.obraId}
            de={filters.de}
            ate={filters.ate}
          />
        </>
      );
    }

    if (!filters.obraId) {
      return (
        <section className="finance-empty">
          <div className="finance-empty-mark" aria-hidden="true">↳</div>
          <div>
            <h2>Selecione uma obra</h2>
            <p>Selecione uma obra para ver preços e PDOR.</p>
          </div>
        </section>
      );
    }

    if (section === "servicos-precos") {
      return (
        <ServicePriceCatalogPage
          key={`prices-${accessVersion}`}
          obraId={filters.obraId}
          permissions={permissions}
        />
      );
    }

    return (
      <FinancePdorSection
        obraId={filters.obraId}
        refreshVersion={accessVersion}
      />
    );
  }

  return (
    <CortexShell
      active="financeiro"
      onRefresh={() => setAccessVersion((version) => version + 1)}
      isRefreshing={Boolean(currentAccess?.loading)}
    >
      <OperationalWorkspace
        className="finance-page"
        eyebrow="Receita"
        title="Financeiro"
      >
        <FinanceSectionIndex
          activeSection={section}
          onSelect={(nextSection) => applyParams(filters, nextSection)}
        />

        {worksiteError ? (
          <div className="finance-error-state" role="alert">
            {worksiteError}
          </div>
        ) : null}

        <section
          className="finance-scope-bar is-revenue"
          aria-label="Escopo da receita"
        >
          <div className="finance-scope-bar__identity">
            <span>Escopo</span>
            <strong>
              {selectedWorksite?.nome ??
                selectedWorksite?.codigoContrato ??
                (filters.obraId
                  ? "Obra selecionada"
                  : "Todas as obras autorizadas")}
            </strong>
          </div>
          <div className="finance-scope-bar__selection">
            <label>
              Obra em análise
              <select
                value={filters.obraId}
                onChange={(event) => selectWorksite(event.target.value)}
              >
                <option value="">Consolidado autorizado</option>
                {worksitesFailed &&
                    filters.obraId &&
                    !selectedWorksite ? (
                  <option value={filters.obraId}>
                    Obra selecionada · validação indisponível
                  </option>
                  ) : null}
                {worksites.map((unit) => (
                  <option
                    key={unit.id}
                    value={unit.id}
                  >
                    {unit.codigoContrato
                      ? `${unit.codigoContrato} · `
                      : ""}
                    {unit.nome ?? unit.id}
                  </option>
                ))}
              </select>
            </label>
          </div>
        </section>

        {worksitesFailed && filters.obraId ? (
          <div className="finance-loading" role="status">
            O escopo selecionado foi preservado porque o catálogo
            autorizado não pôde ser validado. Tente atualizar novamente.
          </div>
        ) : currentAccess?.error && filters.obraId ? (
          <div className="finance-error-state" role="alert">
            <div>
              <strong>Não foi possível validar o acesso desta obra.</strong>
              <span>{currentAccess.error}</span>
            </div>
            <button
              type="button"
              onClick={() => setAccessVersion((version) => version + 1)}
            >
              Tentar novamente
            </button>
          </div>
        ) : selectedWorksitePending ? (
          <div className="finance-loading" role="status">
            Validando o acesso à obra…
          </div>
        ) : !canViewSelectedWorksite ? (
          <section className="finance-denied" role="status">
            <span aria-hidden="true">⊘</span>
            <div>
              <h2>Acesso à receita não concedido</h2>
              <p>
                Seu perfil não possui a capacidade
                FINANCEIRO_VISUALIZAR nesta obra.
              </p>
            </div>
          </section>
        ) : renderSelectedSection()}
      </OperationalWorkspace>
    </CortexShell>
  );
}
