import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type RefObject,
} from "react";

import type { ObraLocalRecord } from "../../lib/db/db.types";
import {
  contextPresentation,
  RDO_CONTEXT_OFFLINE_MISSING,
} from "./rdoCreationContext";
import {
  getCachedRdoCreationContext,
  listCachedAuthorizedRdoWorksites,
  refreshAuthorizedRdoWorksites,
  requireRdoDraftCreationContext,
  type ResolvedRdoDraftCreationContext,
} from "./rdoCreationContextRepository";
import {
  createAndPersistLocalPendingRdoDraft,
  createAndPersistRdoDraft,
  motivoParaNaoCriarRdo,
} from "./rdoDraftCreation";
import type { RdoDraft } from "./rdo.types";
import type { RdoCreationContextLookup } from "./rdoLookupApi";
import { AUTH_SESSION_CHANGED_EVENT } from "../auth/authSession";

import "./RdoCreationDialog.css";

interface RdoCreationDialogProps {
  returnFocusRef: RefObject<HTMLElement | null>;
  initialDraft?: RdoDraft;
  onClose: () => void;
  onCreated: (
    draft: RdoDraft,
    context?: RdoCreationContextLookup,
  ) => void;
}

function normalize(value: string): string {
  return value
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase();
}

function worksiteLabel(worksite: ObraLocalRecord): string {
  return [worksite.nome, worksite.codigoContrato, worksite.cidade, worksite.uf]
    .filter((value) => Boolean(value?.trim()))
    .join(" · ");
}

function focusableElements(element: HTMLElement): HTMLElement[] {
  return Array.from(
    element.querySelectorAll<HTMLElement>(
      "button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])",
    ),
  ).filter((item) => !item.hasAttribute("hidden"));
}

export function RdoCreationDialog({
  returnFocusRef,
  initialDraft,
  onClose,
  onCreated,
}: RdoCreationDialogProps) {
  const titleId = useId();
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const searchRef = useRef<HTMLInputElement | null>(null);
  const requestIdRef = useRef(0);
  const [worksites, setWorksites] = useState<ObraLocalRecord[]>([]);
  const [query, setQuery] = useState("");
  const [selectedWorksiteId, setSelectedWorksiteId] = useState("");
  const [selectedDate, setSelectedDate] = useState(
    initialDraft?.dataRdo ?? "",
  );
  const [contextResult, setContextResult] =
    useState<ResolvedRdoDraftCreationContext | null>(null);
  const [isLoadingWorksites, setIsLoadingWorksites] = useState(true);
  const [isLoadingContext, setIsLoadingContext] = useState(false);
  const [isCreating, setIsCreating] = useState(false);
  const [error, setError] = useState("");
  const [contextRetryVersion, setContextRetryVersion] = useState(0);

  useEffect(() => {
    searchRef.current?.focus();
    let cancelled = false;
    void (async () => {
      try {
        const cached = await listCachedAuthorizedRdoWorksites();
        if (cancelled) return;
        setWorksites(cached);
        setIsLoadingWorksites(false);
        if (navigator.onLine) {
          try {
            const remote = await refreshAuthorizedRdoWorksites();
            if (!cancelled) setWorksites(remote);
          } catch (unknownError) {
            if (!cancelled && cached.length === 0) {
              setError(
                unknownError instanceof Error
                  ? unknownError.message
                  : "Não foi possível carregar as obras autorizadas.",
              );
            }
          }
        }
      } catch (unknownError) {
        if (cancelled) return;
        setIsLoadingWorksites(false);
        setError(
          unknownError instanceof Error
            ? unknownError.message
            : "Não foi possível carregar as obras autorizadas.",
        );
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const discardForSessionChange = () => {
      requestIdRef.current += 1;
      setWorksites([]);
      setSelectedWorksiteId("");
      setSelectedDate("");
      setContextResult(null);
      setError("");
      setIsLoadingWorksites(false);
      setIsLoadingContext(false);
      setIsCreating(false);
      onClose();
    };
    window.addEventListener(
      AUTH_SESSION_CHANGED_EVENT,
      discardForSessionChange,
    );
    return () => {
      window.removeEventListener(
        AUTH_SESSION_CHANGED_EVENT,
        discardForSessionChange,
      );
    };
  }, [onClose]);

  useEffect(() => {
    if (!selectedWorksiteId || !selectedDate) return;
    const requestId = requestIdRef.current + 1;
    requestIdRef.current = requestId;
    void (async () => {
      try {
        const cached = await getCachedRdoCreationContext(
          selectedWorksiteId,
          selectedDate,
        );
        if (requestIdRef.current !== requestId) return;
        if (cached) {
          setContextResult({
            kind: "CANONICAL",
            source: "CACHE",
            cachedAt: cached.cachedAt,
            context: cached.context,
          });
        }
        if (!navigator.onLine) {
          if (!cached) setError(RDO_CONTEXT_OFFLINE_MISSING);
          setIsLoadingContext(false);
          return;
        }
        const resolved = await requireRdoDraftCreationContext(
          selectedWorksiteId,
          selectedDate,
          true,
        );
        if (requestIdRef.current !== requestId) return;
        setContextResult(resolved);
        setIsLoadingContext(false);
      } catch (unknownError) {
        if (requestIdRef.current !== requestId) return;
        setIsLoadingContext(false);
        setError(
          unknownError instanceof Error
            ? unknownError.message
            : RDO_CONTEXT_OFFLINE_MISSING,
        );
      }
    })();
  }, [contextRetryVersion, selectedDate, selectedWorksiteId]);

  function selectWorksite(worksiteId: string) {
    setSelectedWorksiteId(worksiteId);
    setContextResult(null);
    setError("");
    setIsLoadingContext(Boolean(worksiteId && selectedDate));
  }

  function selectDate(date: string) {
    setSelectedDate(date);
    setContextResult(null);
    setError("");
    setIsLoadingContext(Boolean(selectedWorksiteId && date));
  }

  function retryContext() {
    if (!selectedWorksiteId || !selectedDate || isLoadingContext) {
      return;
    }
    setContextResult(null);
    setError("");
    setIsLoadingContext(true);
    setContextRetryVersion((version) => version + 1);
  }

  const visibleWorksites = useMemo(() => {
    const needle = normalize(query.trim());
    if (!needle) return worksites;
    return worksites.filter((worksite) =>
      normalize(worksiteLabel(worksite)).includes(needle),
    );
  }, [query, worksites]);
  const selectedWorksite = worksites.find(
    (worksite) => worksite.id === selectedWorksiteId,
  );

  const presentation = contextResult
    ? contextResult.kind === "CANONICAL"
      ? contextPresentation(contextResult.context)
      : {
          status: "LOCAL_PENDING" as const,
          receiptVersion: null,
          sourceVersion: null,
          label: "Local pendente",
        }
    : null;
  /*
   * O impedimento é apurado com o mesmo critério da gravação.
   *
   * Antes o botão liberava com `isRdoCreationContextComplete`, que só olha a
   * cobertura, e a gravação recusava por mais de vinte outras condições. O
   * botão prometia o que a gravação não cumpria, e a recusa chegava como uma
   * frase que contradizia o painel ao lado.
   */
  const [impedimento, setImpedimento] = useState<string | null>(null);

  useEffect(() => {
    let cancelado = false;
    // Adiado um tique: marcar o estado ainda dentro do efeito encadeia
    // renderizações, e a apuração é assíncrona de qualquer forma.
    const id = window.setTimeout(() => {
      if (!contextResult || contextResult.kind === "LOCAL_PENDING") {
        setImpedimento(null);
        return;
      }
      void motivoParaNaoCriarRdo(
        contextResult.context,
        initialDraft ? { baseDraft: initialDraft } : {},
      )
        .then((motivo) => {
          if (!cancelado) setImpedimento(motivo);
        })
        .catch(() => {
          // Falhar ao apurar não é o mesmo que estar impedido: a gravação
          // continua sendo a autoridade, e ela recusa com o motivo certo.
          if (!cancelado) setImpedimento(null);
        });
    }, 0);
    return () => {
      cancelado = true;
      window.clearTimeout(id);
    };
  }, [contextResult, initialDraft]);

  const canCreate = Boolean(
    selectedWorksiteId &&
      selectedDate &&
      contextResult &&
      (contextResult.kind === "LOCAL_PENDING" || impedimento === null) &&
      !isCreating,
  );

  function closeDialog() {
    if (isCreating) return;
    returnFocusRef.current?.focus();
    onClose();
  }

  function handleDialogKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      closeDialog();
      return;
    }
    if (event.key !== "Tab" || !dialogRef.current) return;
    const focusable = focusableElements(dialogRef.current);
    if (focusable.length === 0) {
      event.preventDefault();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  async function handleCreate() {
    if (!canCreate || !contextResult) return;
    setIsCreating(true);
    setError("");
    try {
      if (contextResult.kind === "LOCAL_PENDING") {
        const created = initialDraft
          ? await createAndPersistLocalPendingRdoDraft(
              contextResult.context,
              { baseDraft: initialDraft },
            )
          : await createAndPersistLocalPendingRdoDraft(
              contextResult.context,
            );
        onCreated(created.draft, undefined);
      } else {
        const created = initialDraft
          ? await createAndPersistRdoDraft(contextResult.context, {
              baseDraft: initialDraft,
            })
          : await createAndPersistRdoDraft(contextResult.context);
        onCreated(created.draft, contextResult.context);
      }
    } catch (unknownError) {
      setError(
        unknownError instanceof Error
          ? unknownError.message
          : "Não foi possível persistir o rascunho local.",
      );
      setIsCreating(false);
    }
  }

  return (
    <div className="rdo-creation-overlay">
      <div
        ref={dialogRef}
        className="rdo-creation-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-busy={isCreating}
        onKeyDown={handleDialogKeyDown}
      >
        <header className="rdo-creation-header">
          <div>
            <p className="rdo-creation-kicker">Córtex · RDO</p>
            <h2 id={titleId}>
              {initialDraft ? "Vincular RDO importado" : "Criar RDO"}
            </h2>
            <p>
              {initialDraft
                ? "Selecione a obra autorizada sem alterar os dados importados."
                : "Selecione a obra que dará origem ao relatório."}
            </p>
          </div>
          <button
            type="button"
            className="rdo-creation-close"
            aria-label="Fechar"
            onClick={closeDialog}
            disabled={isCreating}
          >
            ×
          </button>
        </header>

        <div className="rdo-creation-body">
          <section className="rdo-creation-worksites" aria-label="Obras autorizadas">
            <label>
              Buscar obra
              <input
                ref={searchRef}
                type="search"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
              />
            </label>
            <div
              className="rdo-selected-worksite"
              aria-live="polite"
              data-selected={Boolean(selectedWorksite)}
            >
              <span>Obra selecionada</span>
              <strong>
                {selectedWorksite
                  ? selectedWorksite.nome || selectedWorksite.codigoContrato
                  : "Nenhuma obra selecionada"}
              </strong>
              {selectedWorksite ? (
                <small>
                  {[
                    selectedWorksite.codigoContrato,
                    selectedWorksite.cidade,
                    selectedWorksite.uf,
                  ].filter(Boolean).join(" · ")}
                </small>
              ) : null}
            </div>
            <div className="rdo-creation-worksite-list">
              {isLoadingWorksites ? (
                <p className="rdo-creation-empty">Carregando obras autorizadas…</p>
              ) : null}
              {!isLoadingWorksites && visibleWorksites.length === 0 ? (
                <p className="rdo-creation-empty">
                  Nenhuma obra autorizada está disponível neste aparelho.
                </p>
              ) : null}
              {visibleWorksites.map((worksite) => (
                <label
                  key={worksite.id}
                  className={`rdo-worksite-option${
                    selectedWorksiteId === worksite.id
                      ? " rdo-worksite-option--selected"
                      : ""
                  }`}
                >
                  <input
                    type="radio"
                    name="rdo-worksite"
                    value={worksite.id}
                    checked={selectedWorksiteId === worksite.id}
                    onChange={() => selectWorksite(worksite.id)}
                  />
                  <span>
                    <strong>{worksite.nome || worksite.codigoContrato}</strong>
                    <small>
                      {[worksite.codigoContrato, worksite.cidade, worksite.uf]
                        .filter(Boolean)
                        .join(" · ")}
                    </small>
                  </span>
                </label>
              ))}
            </div>
          </section>

          <aside className="rdo-provenance-rail" aria-label="Proveniência do contexto">
            <h3>Proveniência</h3>
            {!selectedWorksiteId || !selectedDate ? (
              <p>Selecione a obra e a data para consultar a origem persistida.</p>
            ) : null}
            {isLoadingContext && !contextResult ? <p>Consultando contexto…</p> : null}
            {contextResult && presentation ? (
              <dl>
                <div>
                  <dt>Fonte</dt>
                  <dd>
                    {contextResult.source === "SERVER"
                      ? "Servidor"
                      : contextResult.source === "LEGACY_SERVER"
                        ? "Servidor local compatível"
                        : "Cache local"}
                  </dd>
                </div>
                <div>
                  <dt>Contexto</dt>
                  <dd>{presentation.label}</dd>
                </div>
                <div>
                  <dt>Versão</dt>
                  <dd>
                    {contextResult.kind === "LOCAL_PENDING"
                      ? "Receipt canônico pendente"
                      : `Versão ${presentation.sourceVersion}`}
                  </dd>
                </div>
                <div>
                  <dt>RDO anterior</dt>
                  <dd>{contextResult.context.previousRdo?.numeroRdo || "Nenhum RDO anterior"}</dd>
                </div>
                <div>
                  <dt>Equipe importada</dt>
                  <dd>{contextResult.context.previousWorkforce.length} pessoas</dd>
                </div>
                <div>
                  <dt>Cobertura</dt>
                  <dd>
                    {contextResult.kind === "LOCAL_PENDING"
                      ? "Validação canônica antes do sync"
                      : `Colaboradores ${contextResult.context.coverage.colaboradores.returned}/${contextResult.context.coverage.colaboradores.total}`}
                  </dd>
                </div>
              </dl>
            ) : null}
          </aside>
        </div>

        {error ? (
          <div className="rdo-creation-error" role="alert">
            <span>{error}</span>
            {selectedWorksiteId && selectedDate ? (
              <button
                type="button"
                onClick={retryContext}
                disabled={isLoadingContext}
              >
                {isLoadingContext ? "Tentando novamente…" : "Tentar novamente"}
              </button>
            ) : null}
          </div>
        ) : null}

        {/*
          * O impedimento aparece junto do botão que ele desabilita. Botão
          * cinza sem explicação faz a pessoa procurar o que fez de errado —
          * quando o problema quase sempre está no contexto, não nela.
          */}
        {!error && impedimento ? (
          <div className="rdo-creation-error" role="status">
            <span>{impedimento}</span>
            {selectedWorksiteId && selectedDate ? (
              <button
                type="button"
                onClick={retryContext}
                disabled={isLoadingContext}
              >
                {isLoadingContext
                  ? "Recarregando…"
                  : "Recarregar contexto"}
              </button>
            ) : null}
          </div>
        ) : null}

        <footer className="rdo-creation-footer">
          <label>
            Data do RDO
            <input
              type="date"
              value={selectedDate}
              onChange={(event) => selectDate(event.target.value)}
              required
            />
          </label>
          <div className="rdo-creation-actions">
            <button type="button" onClick={closeDialog} disabled={isCreating}>
              Cancelar
            </button>
            <button
              type="button"
              className="rdo-creation-primary"
              onClick={() => void handleCreate()}
              disabled={!canCreate}
            >
              {isCreating ? "Persistindo…" : "Criar rascunho"}
            </button>
          </div>
        </footer>
      </div>
    </div>
  );
}
