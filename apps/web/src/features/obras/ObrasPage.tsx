import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from "react";

import { CortexShell } from "../../components/shell/CortexShell";
import { OperationalWorkspace } from "../../components/workspace/OperationalWorkspace";
import type {
  ObraLocalRecord,
  OperationalEventRecord,
} from "../../lib/db/db.types";
import {
  filterObrasByChip,
  filterObrasByRodovia,
  filterObrasByUf,
  filterOperationalObras,
  OBRA_STATUS_CHIPS,
  type ObraStatusChip,
} from "../home/homeFilters";
import { useHomeData } from "../home/useHomeData";
import { getSession, isAlfa } from "../auth/authSession";
import {
  buscarPdorAtual,
  buscarTimelineObra,
  type ObraPdor,
  type ObraTimelineEvent,
} from "./obrasApi";
import { NovaObraForm } from "./gestao/NovaObraForm";
import {
  queueArchiveObra,
  queueActivateObra,
  queueDeactivateObra,
  queueRestoreObra,
  queueUpdateObra,
  type UpdateObraInput,
} from "./obraLifecycle";
import { ObraAccessibleDialog } from "./ObraAccessibleDialog";
import { ObraTrechoSection } from "./trecho/ObraTrechoSection";
import "./gestao/gestaoObras.css";

type ObrasView = "ATIVAS" | "DESATIVADAS" | "LIXEIRA";

const EMPTY_UPDATE: UpdateObraInput = {
  codigoContrato: "",
  codigoInterno: null,
  nome: "",
  cliente: null,
  descricao: null,
  cidade: null,
  uf: null,
  rodovia: null,
  fonteArquivo: null,
  observacoes: null,
};

const TRACE_KEYS = [
  "codigoContrato",
  "nome",
  "cliente",
  "cidade",
  "uf",
  "rodovia",
  "status",
  "observacoes",
];

function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return "";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}

function formatCurrency(value: number | null): string {
  if (value === null) {
    return "-";
  }

  return new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
    maximumFractionDigits: 0,
  }).format(value);
}

function formatDateOnly(value: string | null): string {
  if (!value) {
    return "";
  }

  const date = new Date(
    value.includes("T") ? value : `${value}T00:00:00`,
  );

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
  }).format(date);
}

function formatPercent(value: number | null): string {
  if (value === null) {
    return "-";
  }

  return new Intl.NumberFormat("pt-BR", {
    style: "percent",
    maximumFractionDigits: 1,
  }).format(value);
}

function riskClass(risco: string | null): string {
  const normalized = (risco ?? "").toUpperCase();

  if (normalized === "CRITICAL" || normalized === "HIGH") {
    return "obras-pdor-risk obras-pdor-risk--alto";
  }

  if (normalized === "MODERATE") {
    return "obras-pdor-risk obras-pdor-risk--medio";
  }

  if (normalized === "LOW") {
    return "obras-pdor-risk obras-pdor-risk--baixo";
  }

  return "obras-pdor-risk";
}

function payloadSummary(
  payload: Record<string, unknown>,
): string {
  return TRACE_KEYS
    .map((key) => {
      const value = payload[key];

      if (
        value === null ||
        value === undefined ||
        value === ""
      ) {
        return null;
      }

      return `${key}: ${String(value)}`;
    })
    .filter((value): value is string => Boolean(value))
    .join(" · ");
}

function localEventToTrace(
  event: OperationalEventRecord,
): ObraTimelineEvent {
  return {
    id: event.id,
    commitSeq: null,
    type: event.type,
    principalEntityType: event.principalEntity.tipo,
    principalEntityId: event.principalEntity.id,
    obraId: event.obraId,
    occurredAt: event.occurredAt,
    origin: event.origin,
    syncStatus: event.syncStatus,
    payload: event.payload,
  };
}

function obraSubtitle(obra: ObraLocalRecord): string {
  return [
    obra.codigoContrato,
    obra.cliente,
    [obra.cidade, obra.uf].filter(Boolean).join("/"),
    obra.rodovia,
  ]
    .filter(Boolean)
    .join(" · ");
}

function syncLabel(obra: ObraLocalRecord): string | null {
  switch (obra.syncStatus) {
    case "LOCAL_ONLY":
    case "LOCAL_PENDING":
    case "PENDING_SYNC":
      return "Pendente";
    case "SYNCING":
      return "Sincronizando";
    case "CONFLICT":
      return "Conflito";
    case "ERROR":
      return "Erro";
    default:
      return null;
  }
}

function updateInputFromObra(
  obra: ObraLocalRecord,
): UpdateObraInput {
  return {
    codigoContrato: obra.codigoContrato,
    codigoInterno: obra.codigoInterno ?? null,
    nome: obra.nome,
    cliente: obra.cliente,
    descricao: obra.descricao ?? null,
    cidade: obra.cidade,
    uf: obra.uf,
    rodovia: obra.rodovia,
    fonteArquivo: obra.fonteArquivo ?? null,
    observacoes: obra.observacoes,
  };
}

export function ObrasPage() {
  const {
    obras,
    focusedObraId: hydratedFocusedObraId,
    setFocusedObraId,
    events,
    isLoading,
    hasConfirmedRemoteHydration,
    reload,
  } = useHomeData({ includeArchived: true });
  const [obraOverrides, setObraOverrides] = useState<
    Record<string, ObraLocalRecord>
  >({});
  const [selectedObraId, setSelectedObraId] = useState(
    hydratedFocusedObraId,
  );
  const [view, setView] = useState<ObrasView>("ATIVAS");
  const [chip, setChip] =
    useState<ObraStatusChip>("TODAS");
  const [ufFilter, setUfFilter] = useState("");
  const [rodoviaFilter, setRodoviaFilter] = useState("");
  const [timeline, setTimeline] = useState<
    ObraTimelineEvent[]
  >([]);
  const [timelineObraId, setTimelineObraId] =
    useState<string | null>(null);
  const [timelineError, setTimelineError] =
    useState<string | null>(null);
  const [isTimelineLoading, setIsTimelineLoading] =
    useState(false);
  const [pdor, setPdor] = useState<ObraPdor | null>(null);
  const [pdorError, setPdorError] =
    useState<string | null>(null);
  const [isPdorLoading, setIsPdorLoading] =
    useState(false);
  const [showCreateWorksite, setShowCreateWorksite] =
    useState(false);
  const [editingObra, setEditingObra] =
    useState<ObraLocalRecord | null>(null);
  const [editInput, setEditInput] =
    useState<UpdateObraInput>(EMPTY_UPDATE);
  const [archiveCandidate, setArchiveCandidate] =
    useState<ObraLocalRecord | null>(null);
  const [isMutating, setIsMutating] = useState(false);
  const [actionError, setActionError] =
    useState<string | null>(null);
  const [isOntologyOpen, setIsOntologyOpen] =
    useState(false);
  const editTriggerRef = useRef<HTMLButtonElement>(null);
  const archiveTriggerRef = useRef<HTMLButtonElement>(null);
  const editInitialFocusRef = useRef<HTMLInputElement>(null);
  const archiveInitialFocusRef =
    useRef<HTMLButtonElement>(null);
  const trashRef = useRef<HTMLElement>(null);
  const canManageWorksites = isAlfa(getSession());

  const localObras = useMemo(
    () =>
      obras.map((obra) => {
        const override = obraOverrides[obra.id];
        if (!override) return obra;
        const cachedVersion = obra.versaoEntidade ?? -1;
        const optimisticVersion =
          override.versaoEntidade ?? -1;
        if (
          cachedVersion > optimisticVersion ||
          obra.updatedAt >= override.updatedAt
        ) {
          return obra;
        }
        return override;
      }),
    [obraOverrides, obras],
  );

  const operationalObras = useMemo(
    () => filterOperationalObras(localObras),
    [localObras],
  );
  const archivedObras = useMemo(
    () => localObras.filter((obra) => obra.arquivadoEm != null),
    [localObras],
  );

  const ufs = useMemo(
    () =>
      [...new Set(operationalObras.map((obra) => obra.uf))].filter(
        (uf): uf is string => Boolean(uf),
      ),
    [operationalObras],
  );

  const rodovias = useMemo(
    () =>
      [
        ...new Set(operationalObras.map((obra) => obra.rodovia)),
      ].filter((rodovia): rodovia is string =>
        Boolean(rodovia),
      ),
    [operationalObras],
  );

  const filteredObras = useMemo(
    () => {
      const inactiveIds = new Set(
        filterObrasByChip(
          operationalObras,
          "DESATIVADAS",
        ).map((obra) => obra.id),
      );
      const byView = view === "DESATIVADAS"
        ? operationalObras.filter((obra) =>
            inactiveIds.has(obra.id)
          )
        : operationalObras.filter((obra) =>
            !inactiveIds.has(obra.id)
          );
      const byChip = view === "ATIVAS"
        ? filterObrasByChip(byView, chip)
        : byView;
      return (
      filterObrasByRodovia(
        filterObrasByUf(
            byChip,
          ufFilter,
        ),
        rodoviaFilter,
        )
      );
    },
    [
      operationalObras,
      view,
      chip,
      ufFilter,
      rodoviaFilter,
    ],
  );

  const focusedObra = useMemo(
    () => {
      const candidates =
        view === "LIXEIRA" ? archivedObras : filteredObras;
      return candidates.find(
        (obra) => obra.id === selectedObraId,
      ) ?? candidates[0] ?? null;
    },
    [
      archivedObras,
      filteredObras,
      selectedObraId,
      view,
    ],
  );

  const focusedObraId =
    view === "LIXEIRA" ? null : focusedObra?.id ?? null;

  useEffect(() => {
    if (
      focusedObraId &&
      focusedObraId !== hydratedFocusedObraId
    ) {
      setFocusedObraId(focusedObraId);
    }
  }, [
    focusedObraId,
    hydratedFocusedObraId,
    setFocusedObraId,
  ]);

  useEffect(() => {
    let cancelled = false;
    if (!focusedObraId) {
      queueMicrotask(() => {
        if (!cancelled) {
          setTimeline([]);
          setTimelineObraId(null);
          setTimelineError(null);
          setIsTimelineLoading(false);
        }
      });
      return () => {
        cancelled = true;
      };
    }

    queueMicrotask(() => {
      if (!cancelled) {
        setIsTimelineLoading(true);
        setTimelineError(null);
      }
    });

    buscarTimelineObra(focusedObraId)
      .then((items) => {
        if (!cancelled) {
          setTimeline(items);
          setTimelineObraId(focusedObraId);
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setTimeline([]);
          setTimelineObraId(focusedObraId);
          setTimelineError(
            error instanceof Error
              ? error.message
              : "Timeline indisponivel.",
          );
        }
      })
      .finally(() => {
        if (!cancelled) {
          setIsTimelineLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [focusedObraId]);

  useEffect(() => {
    let cancelled = false;
    if (!focusedObraId) {
      queueMicrotask(() => {
        if (!cancelled) {
          setPdor(null);
          setPdorError(null);
          setIsPdorLoading(false);
        }
      });
      return () => {
        cancelled = true;
      };
    }

    queueMicrotask(() => {
      if (!cancelled) {
        setIsPdorLoading(true);
        setPdorError(null);
      }
    });

    buscarPdorAtual(focusedObraId)
      .then((result) => {
        if (!cancelled) {
          setPdor(result);
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setPdor(null);
          setPdorError(
            error instanceof Error
              ? error.message
              : "Previsão de receita indisponível.",
          );
        }
      })
      .finally(() => {
        if (!cancelled) {
          setIsPdorLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [focusedObraId]);

  const localTrace = useMemo(
    () =>
      focusedObraId
        ? events
          .filter(
            (event) =>
              event.obraId === focusedObraId ||
              (
                event.principalEntity.tipo === "OBRA" &&
                event.principalEntity.id === focusedObraId
              ),
          )
          .map(localEventToTrace)
        : [],
    [events, focusedObraId],
  );
  const focusedTimeline = useMemo(
    () =>
      focusedObraId === timelineObraId ? timeline : [],
    [focusedObraId, timeline, timelineObraId],
  );
  const traceEvents =
    focusedTimeline.length > 0 ? focusedTimeline : localTrace;
  const traceSource =
    focusedTimeline.length > 0
      ? "Cortex online"
      : localTrace.length > 0
        ? "Cortex local"
        : "Sem eventos";

  function selectObra(obraId: string) {
    setSelectedObraId(obraId);
    setFocusedObraId(obraId);
    setIsOntologyOpen(false);
    setActionError(null);
  }

  function replaceLocalObra(updated: ObraLocalRecord) {
    setObraOverrides((current) => ({
      ...current,
      [updated.id]: updated,
    }));
    setSelectedObraId(updated.id);
    reload();
  }

  async function applyLifecycleMutation(
    operation: () => Promise<ObraLocalRecord>,
    nextView?: ObrasView,
  ) {
    setIsMutating(true);
    setActionError(null);
    try {
      const updated = await operation();
      replaceLocalObra(updated);
      if (nextView) {
        setView(nextView);
      }
      return true;
    } catch (error: unknown) {
      setActionError(
        error instanceof Error
          ? error.message
          : "Não foi possível alterar a obra.",
      );
      return false;
    } finally {
      setIsMutating(false);
    }
  }

  function beginEdit(obra: ObraLocalRecord) {
    setEditingObra(obra);
    setEditInput(updateInputFromObra(obra));
    setActionError(null);
  }

  function beginArchive(obra: ObraLocalRecord) {
    setArchiveCandidate(obra);
    setActionError(null);
  }

  function closeEdit() {
    if (isMutating) return;
    setEditingObra(null);
    setActionError(null);
  }

  function closeArchive() {
    if (isMutating) return;
    setArchiveCandidate(null);
    setActionError(null);
  }

  function patchEdit(values: Partial<UpdateObraInput>) {
    setEditInput((current) => ({ ...current, ...values }));
  }

  async function submitEdit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!editingObra) return;
    const completed = await applyLifecycleMutation(
      () => queueUpdateObra(editingObra, editInput),
    );
    if (completed) {
      setEditingObra(null);
    }
  }

  async function deactivate(obra: ObraLocalRecord) {
    await applyLifecycleMutation(
      () => queueDeactivateObra(obra),
      "DESATIVADAS",
    );
  }

  // O caminho de volta. Antes o botão só desativava e ficava desabilitado
  // depois disso — quem desativou por engano não tinha saída pelo produto, nem
  // pela Lixeira: restaurar desfaz o arquivamento e não toca no status.
  async function activate(obra: ObraLocalRecord) {
    await applyLifecycleMutation(
      () => queueActivateObra(obra),
      "ATIVAS",
    );
  }

  async function confirmArchive() {
    if (!archiveCandidate) return;
    const completed = await applyLifecycleMutation(
      () => queueArchiveObra(archiveCandidate),
      "LIXEIRA",
    );
    if (completed) {
      setArchiveCandidate(null);
    }
  }

  async function restore(obra: ObraLocalRecord) {
    await applyLifecycleMutation(
      () => queueRestoreObra(obra),
    );
  }

  return (
    <CortexShell
      active="obras"
      onRefresh={reload}
      isRefreshing={isLoading}
    >
      <OperationalWorkspace
        className="obras-page"
        eyebrow="Obras · Escopo operacional"
        title="Obras"
        actions={canManageWorksites ? (
            <button
              type="button"
              className="obras-create-action"
              onClick={() => setShowCreateWorksite(true)}
            >
              Criar obra
            </button>
          ) : null}
        tabs={[
          {
            id: "ATIVAS",
            label: "Ativas",
            active: view === "ATIVAS",
          },
          {
            id: "DESATIVADAS",
            label: "Desativadas",
            active: view === "DESATIVADAS",
          },
          ...(canManageWorksites
            ? [{
                id: "LIXEIRA",
                label: "Lixeira",
                active: view === "LIXEIRA",
              }]
            : []),
        ]}
        onTabChange={(id) => {
          setView(id as ObrasView);
          setIsOntologyOpen(false);
          setActionError(null);
        }}
        status={{
          code: isLoading
            ? "SYNCING"
            : hasConfirmedRemoteHydration
              ? "SYNCED"
              : "LOCAL",
          label: isLoading
            ? "Atualizando obras"
            : view === "LIXEIRA"
              ? `${archivedObras.length} obras na Lixeira`
              : `${filteredObras.length} obras visíveis`,
          detail: hasConfirmedRemoteHydration
            ? focusedObra
              ? `Foco: ${focusedObra.nome}`
              : "Nenhuma obra selecionada"
            : focusedObra
              ? `Cache local · Foco: ${focusedObra.nome}`
              : "Dados preservados neste dispositivo",
        }}
      >
        {view !== "LIXEIRA" ? (
        <section className="obras-filter-bar" aria-label="Filtros de obras">
          {view === "ATIVAS" ? (
          <div
            className="home-chips"
            role="group"
            aria-label="Filtrar obras por status"
          >
            {OBRA_STATUS_CHIPS
              .filter((option) => option.value !== "DESATIVADAS")
              .map((option) => (
              <button
                key={option.value}
                type="button"
                className={
                  option.value === chip
                    ? "chip chip--active"
                    : "chip"
                }
                onClick={() => setChip(option.value)}
              >
                {option.label}
              </button>
              ))}
          </div>
          ) : null}
          <div className="home-uf-filter">
            <span>Filtrar por:</span>
            <select
              value={ufFilter}
              aria-label="Filtrar por UF"
              onChange={(event) =>
                setUfFilter(event.target.value)
              }
            >
              <option value="">UF: todas</option>
              {ufs.map((uf) => (
                <option key={uf} value={uf}>
                  {uf}
                </option>
              ))}
            </select>
            <select
              value={rodoviaFilter}
              aria-label="Filtrar por rodovia"
              onChange={(event) =>
                setRodoviaFilter(event.target.value)
              }
            >
              <option value="">Rodovia: todas</option>
              {rodovias.map((rodovia) => (
                <option key={rodovia} value={rodovia}>
                  {rodovia}
                </option>
              ))}
            </select>
          </div>
        </section>
        ) : null}

        {view === "LIXEIRA" && canManageWorksites ? (
          <section
            ref={trashRef}
            className="obras-trash"
            aria-label="Lixeira de obras"
            tabIndex={-1}
          >
            {actionError ? (
              <p className="obras-action-error" role="alert">
                {actionError}
              </p>
            ) : null}
            {archivedObras.length === 0 ? (
              <p className="obras-empty">
                Nenhuma obra na Lixeira.
              </p>
            ) : (
              <ul className="obras-trash-list">
                {archivedObras.map((obra) => (
                  <li key={obra.id}>
                    <div>
                      <strong>{obra.nome || obra.codigoContrato}</strong>
                      <span className="obras-trash-id">
                        {obra.codigoContrato || obra.id}
                      </span>
                    </div>
                    <span className="obras-trash-status">
                      {obra.status}
                    </span>
                    <time dateTime={obra.arquivadoEm ?? undefined}>
                      {formatDateOnly(obra.arquivadoEm ?? null) || "-"}
                    </time>
                    {syncLabel(obra) ? (
                      <span
                        className={`obras-sync-state is-${obra.syncStatus?.toLowerCase()}`}
                      >
                        {syncLabel(obra)}
                      </span>
                    ) : null}
                    <button
                      type="button"
                      disabled={isMutating}
                      onClick={() => void restore(obra)}
                    >
                      Restaurar
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        ) : (
        <section className="obras-workspace">
          <aside
            className="obras-list"
            aria-label="Selecionar obra"
          >
            {isLoading && obras.length === 0 ? (
              <p className="obras-empty">Carregando obras...</p>
            ) : filteredObras.length === 0 ? (
              <p className="obras-empty">
                Nenhuma obra encontrada.
              </p>
            ) : (
              filteredObras.map((obra) => (
                <button
                  key={obra.id}
                  type="button"
                  className={
                    obra.id === focusedObra?.id
                      ? "obras-list-item active"
                      : "obras-list-item"
                  }
                  aria-current={
                    obra.id === focusedObra?.id ? "true" : undefined
                  }
                  title={`${obra.nome} — ${obraSubtitle(obra) || obra.id}`}
                  onClick={() => selectObra(obra.id)}
                >
                  <span className="obras-list-title">
                    {obra.nome}
                  </span>
                  <span className="obras-list-meta">
                    {obraSubtitle(obra) || obra.id}
                  </span>
                </button>
              ))
            )}
          </aside>

          <section className="obras-detail">
            {focusedObra ? (
              <>
                <div className="obras-detail-header">
                  <div>
                    <div className="obras-detail-state">
                      <span className="home-obra-pill">
                        {focusedObra.status}
                      </span>
                      {syncLabel(focusedObra) ? (
                        <span
                          className={`obras-sync-state is-${focusedObra.syncStatus?.toLowerCase()}`}
                        >
                          {syncLabel(focusedObra)}
                        </span>
                      ) : null}
                    </div>
                    <h2>{focusedObra.nome}</h2>
                    <p>{obraSubtitle(focusedObra) || "-"}</p>
                  </div>
                  {canManageWorksites ? (
                    <div
                      className="obras-lifecycle-actions"
                      aria-label="Ações da obra"
                    >
                      <button
                        ref={editTriggerRef}
                        type="button"
                        disabled={isMutating}
                        onClick={() => beginEdit(focusedObra)}
                      >
                        Editar
                      </button>
                      {focusedObra.status === "INATIVA" ? (
                        <button
                          type="button"
                          disabled={isMutating}
                          onClick={() => void activate(focusedObra)}
                        >
                          Ativar
                        </button>
                      ) : (
                        <button
                          type="button"
                          disabled={isMutating}
                          onClick={() => void deactivate(focusedObra)}
                        >
                          Desativar
                        </button>
                      )}
                      <button
                        ref={archiveTriggerRef}
                        type="button"
                        className="is-danger"
                        disabled={isMutating}
                        onClick={() => beginArchive(focusedObra)}
                      >
                        Excluir
                      </button>
                    </div>
                  ) : null}
                </div>

                {actionError &&
                !editingObra &&
                !archiveCandidate ? (
                  <p className="obras-action-error" role="alert">
                    {actionError}
                  </p>
                ) : null}

                <dl className="obras-facts">
                  <div>
                    <dt>Contrato</dt>
                    <dd>
                      {focusedObra.codigoContrato || "-"}
                    </dd>
                  </div>
                  <div>
                    <dt>Valor contratual</dt>
                    <dd>
                      {formatCurrency(
                        focusedObra.valorContratual,
                      )}
                    </dd>
                  </div>
                  <div>
                    <dt>Localizacao</dt>
                    <dd>
                      {[focusedObra.cidade, focusedObra.uf]
                        .filter(Boolean)
                        .join("/") || "-"}
                    </dd>
                  </div>
                  <div>
                    <dt>Rodovia</dt>
                    <dd>{focusedObra.rodovia || "-"}</dd>
                  </div>
                  <div>
                    <dt>Coordenadas</dt>
                    <dd>
                      {focusedObra.latitude !== null &&
                      focusedObra.longitude !== null
                        ? `${focusedObra.latitude}, ${focusedObra.longitude}`
                        : "-"}
                    </dd>
                  </div>
                  <div>
                    <dt>Atualizado em</dt>
                    <dd>
                      {formatDateTime(focusedObra.updatedAt) ||
                        "-"}
                    </dd>
                  </div>
                </dl>

                <ObraTrechoSection
                  key={focusedObra.id}
                  obra={{
                    id: focusedObra.id,
                    nome: focusedObra.nome,
                    latitude: focusedObra.latitude,
                    longitude: focusedObra.longitude,
                  }}
                  podeDesenhar={canManageWorksites}
                  endereco={{
                    cidade: focusedObra.cidade,
                    uf: focusedObra.uf,
                    rodovia: focusedObra.rodovia,
                  }}
                  operavelLocalmente={
                    !focusedObra.arquivadoEm &&
                    focusedObra.status !== "INATIVA"
                  }
                />

                <section
                  className="obras-pdor"
                  aria-label="Previsão de receita PDOR"
                >
                  <div className="obras-pdor-header">
                    <div>
                      <h3>Previsão de receita · PDOR</h3>
                      <span>
                        {pdor?.dataReferencia
                          ? `Referência ${formatDateOnly(pdor.dataReferencia)}`
                          : "Calculado a partir dos RDOs da obra"}
                      </span>
                    </div>
                    {pdor?.riscoLabel ? (
                      <span className={riskClass(pdor.risco)}>
                        {pdor.riscoLabel}
                      </span>
                    ) : null}
                  </div>

                  {isPdorLoading ? (
                    <p className="obras-pdor-note">
                      Consultando previsão de receita...
                    </p>
                  ) : pdorError ? (
                    <p className="obras-pdor-note">
                      {pdorError}
                    </p>
                  ) : !pdor ? (
                    <p className="obras-pdor-note">
                      Nenhum cálculo PDOR registrado ainda. O
                      próximo RDO sincronizado dispara o cálculo
                      automaticamente.
                    </p>
                  ) : pdor.statusExecucao !== "SUCCESS" ? (
                    <div className="obras-pdor-insufficient">
                      <strong>
                        {pdor.statusExecucaoLabel ??
                          pdor.statusExecucao}
                      </strong>
                      <p>
                        {pdor.erroExecucao ??
                          "O PDOR não pôde ser calculado com os dados atuais."}
                      </p>
                    </div>
                  ) : (
                    <>
                      <dl className="obras-pdor-grid">
                        <div className="obras-pdor-main">
                          <dt>Receita prevista final</dt>
                          <dd>
                            {formatCurrency(
                              pdor.receitaPrevistaFinal ??
                                pdor.p50,
                            )}
                          </dd>
                          <dd className="obras-pdor-range">
                            Faixa {formatCurrency(pdor.p10)} a{" "}
                            {formatCurrency(pdor.p95)} · P50{" "}
                            {formatCurrency(pdor.p50)}
                          </dd>
                        </div>
                        <div>
                          <dt>Risco de ficar abaixo do contrato</dt>
                          <dd>
                            {formatPercent(
                              pdor.probabilidadeAbaixoContrato,
                            )}
                          </dd>
                        </div>
                        <div>
                          <dt>Confiança do cálculo</dt>
                          <dd>{formatPercent(pdor.confianca)}</dd>
                        </div>
                        <div>
                          <dt>Calibração</dt>
                          <dd>
                            {pdor.calibracaoLabel ??
                              pdor.calibracao ??
                              "-"}
                          </dd>
                        </div>
                      </dl>

                      {pdor.drivers.length > 0 ? (
                        <ul className="obras-pdor-drivers">
                          {pdor.drivers
                            .slice(0, 3)
                            .map((driver) => (
                              <li key={driver.code || driver.description}>
                                <strong>
                                  {driver.description}
                                </strong>
                                {driver.evidence ? (
                                  <span>{driver.evidence}</span>
                                ) : null}
                              </li>
                            ))}
                        </ul>
                      ) : null}
                    </>
                  )}
                </section>

                <div className="obras-ontology">
                  <button
                    type="button"
                    className="obras-ontology-toggle"
                    aria-expanded={isOntologyOpen}
                    aria-controls="obras-ontology-content"
                    onClick={() =>
                      setIsOntologyOpen((current) => !current)
                    }
                  >
                    <span aria-hidden="true">
                      {isOntologyOpen ? "▾" : "▸"}
                    </span>
                    Ontologia da obra
                  </button>
                  {isOntologyOpen ? (
                  <section
                    id="obras-ontology-content"
                    className="obras-trace"
                  >
                    <div className="obras-trace-header">
                      <div>
                        <h3>Rastreabilidade Cortex</h3>
                        <span>{traceSource}</span>
                      </div>
                      <span className="obras-trace-status">
                        {isTimelineLoading
                          ? "consultando"
                          : timelineError
                            ? "local"
                            : "sincronizado"}
                      </span>
                    </div>

                    {timelineError ? (
                      <p className="obras-trace-note">
                        {timelineError}
                      </p>
                    ) : null}

                    {traceEvents.length === 0 ? (
                      <p className="obras-empty">
                        Nenhum evento operacional registrado.
                      </p>
                    ) : (
                      <ol className="obras-trace-list">
                        {traceEvents.map((event) => (
                          <li key={event.id}>
                            <div>
                              <strong>{event.type}</strong>
                              <span>
                                {formatDateTime(
                                  event.occurredAt,
                                ) || "-"}
                              </span>
                            </div>
                            <p>
                              {payloadSummary(event.payload) ||
                                event.principalEntityId}
                            </p>
                            <small>
                              {[
                                event.origin,
                                event.syncStatus,
                                event.commitSeq === null
                                  ? null
                                  : `commit ${event.commitSeq}`,
                              ]
                                .filter(Boolean)
                                .join(" · ")}
                            </small>
                          </li>
                        ))}
                      </ol>
                    )}
                  </section>
                  ) : null}
                </div>
              </>
            ) : (
              <p className="obras-empty">
                Nenhuma obra disponivel neste dispositivo.
              </p>
            )}
          </section>
        </section>
        )}

        {editingObra ? (
          <div
            className="nova-obra-dialog-backdrop"
            role="presentation"
          >
            <ObraAccessibleDialog
              className="nova-obra-dialog obras-edit-dialog"
              labelledBy="obras-edit-title"
              initialFocusRef={editInitialFocusRef}
              returnFocusRef={editTriggerRef}
              closeDisabled={isMutating}
              onClose={closeEdit}
            >
              <header>
                <h2 id="obras-edit-title">Editar obra</h2>
                <button
                  type="button"
                  aria-label="Fechar edição da obra"
                  disabled={isMutating}
                  onClick={closeEdit}
                >
                  ×
                </button>
              </header>
              <form
                className="obras-edit-form"
                onSubmit={(event) => void submitEdit(event)}
              >
                <div className="obras-edit-grid">
                  <label>
                    Código do contrato
                    <input
                      ref={editInitialFocusRef}
                      value={editInput.codigoContrato}
                      onChange={(event) =>
                        patchEdit({
                          codigoContrato: event.target.value,
                        })
                      }
                    />
                  </label>
                  <label>
                    Código interno
                    <input
                      value={editInput.codigoInterno ?? ""}
                      onChange={(event) =>
                        patchEdit({
                          codigoInterno: event.target.value,
                        })
                      }
                    />
                  </label>
                  <label className="is-wide">
                    Nome
                    <input
                      value={editInput.nome}
                      onChange={(event) =>
                        patchEdit({ nome: event.target.value })
                      }
                    />
                  </label>
                  <label className="is-wide">
                    Cliente
                    <input
                      value={editInput.cliente ?? ""}
                      onChange={(event) =>
                        patchEdit({ cliente: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    Cidade
                    <input
                      value={editInput.cidade ?? ""}
                      onChange={(event) =>
                        patchEdit({ cidade: event.target.value })
                      }
                    />
                  </label>
                  <label>
                    UF
                    <input
                      maxLength={2}
                      value={editInput.uf ?? ""}
                      onChange={(event) =>
                        patchEdit({
                          uf: event.target.value.toUpperCase(),
                        })
                      }
                    />
                  </label>
                  <label className="is-wide">
                    Rodovia
                    <input
                      value={editInput.rodovia ?? ""}
                      onChange={(event) =>
                        patchEdit({ rodovia: event.target.value })
                      }
                    />
                  </label>
                  <label className="is-wide">
                    Descrição
                    <textarea
                      value={editInput.descricao ?? ""}
                      onChange={(event) =>
                        patchEdit({ descricao: event.target.value })
                      }
                    />
                  </label>
                  <label className="is-wide">
                    Fonte do arquivo
                    <input
                      value={editInput.fonteArquivo ?? ""}
                      onChange={(event) =>
                        patchEdit({
                          fonteArquivo: event.target.value,
                        })
                      }
                    />
                  </label>
                  <label className="is-wide">
                    Observações
                    <textarea
                      value={editInput.observacoes ?? ""}
                      onChange={(event) =>
                        patchEdit({
                          observacoes: event.target.value,
                        })
                      }
                    />
                  </label>
                </div>
                {actionError ? (
                  <p className="obras-action-error" role="alert">
                    {actionError}
                  </p>
                ) : null}
                <footer>
                  <button
                    type="button"
                    disabled={isMutating}
                    onClick={closeEdit}
                  >
                    Cancelar
                  </button>
                  <button type="submit" disabled={isMutating}>
                    {isMutating
                      ? "Salvando…"
                      : "Salvar alterações"}
                  </button>
                </footer>
              </form>
            </ObraAccessibleDialog>
          </div>
        ) : null}

        {archiveCandidate ? (
          <div
            className="nova-obra-dialog-backdrop"
            role="presentation"
          >
            <ObraAccessibleDialog
              className="nova-obra-dialog obras-confirm-dialog"
              labelledBy="obras-archive-title"
              initialFocusRef={archiveInitialFocusRef}
              returnFocusRef={archiveTriggerRef}
              returnFallbackRef={trashRef}
              closeDisabled={isMutating}
              onClose={closeArchive}
            >
              <header>
                <h2 id="obras-archive-title">Excluir obra</h2>
              </header>
              <p>
                A obra poderá ser restaurada na Lixeira.
              </p>
              {actionError ? (
                <p className="obras-action-error" role="alert">
                  {actionError}
                </p>
              ) : null}
              <footer>
                <button
                  ref={archiveInitialFocusRef}
                  type="button"
                  disabled={isMutating}
                  onClick={closeArchive}
                >
                  Cancelar
                </button>
                <button
                  type="button"
                  className="is-danger"
                  disabled={isMutating}
                  onClick={() => void confirmArchive()}
                >
                  {isMutating ? "Excluindo…" : "Excluir obra"}
                </button>
              </footer>
            </ObraAccessibleDialog>
          </div>
        ) : null}

        {showCreateWorksite ? (
          <div
            className="nova-obra-dialog-backdrop"
            role="presentation"
            onMouseDown={(event) => {
              if (event.currentTarget === event.target) {
                setShowCreateWorksite(false);
              }
            }}
          >
            <section
              className="nova-obra-dialog"
              role="dialog"
              aria-modal="true"
              aria-labelledby="nova-obra-dialog-title"
            >
              <header>
                <div>
                  <h2 id="nova-obra-dialog-title">Criar obra</h2>
                  <p>
                    A obra entra no escopo global e na rastreabilidade
                    operacional assim que for criada.
                  </p>
                </div>
                <button
                  type="button"
                  aria-label="Fechar cadastro de obra"
                  onClick={() => setShowCreateWorksite(false)}
                >
                  ×
                </button>
              </header>
              <NovaObraForm
                onCancel={() => setShowCreateWorksite(false)}
                onCreated={(created) => {
                  setShowCreateWorksite(false);
                  setView("ATIVAS");
                  setChip("TODAS");
                  setUfFilter("");
                  setRodoviaFilter("");
                  setSelectedObraId(created.id);
                  setFocusedObraId(created.id);
                  reload();
                }}
              />
            </section>
          </div>
        ) : null}
      </OperationalWorkspace>
    </CortexShell>
  );
}
