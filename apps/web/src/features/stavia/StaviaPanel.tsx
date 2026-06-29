import {
  type CSSProperties,
  type ChangeEvent,
  type FormEvent,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import {
  adicionarContextoStavia,
  baixarSnapshotStavia,
  consultarStavia,
  StaviaApiError,
} from "./staviaApi";
import {
  getBestAvailableStaviaSnapshot,
  saveStaviaSnapshot,
} from "./staviaSnapshotStorage";
import { responderComSnapshotStavia } from "./staviaLocalEngine";
import type {
  StaviaConfidence,
  StaviaConsultaResponse,
  StaviaSnapshot,
  StaviaSnapshotObra,
  StaviaSnapshotRdo,
} from "./stavia.types";
import "./StaviaPanel.css";

interface StaviaPanelProps {
  initialObraId?: string;
  initialRdoId?: string;
  onBack?: () => void;
  variant?: "page" | "floating";
  isOpen?: boolean;
  onOpenChange?: (isOpen: boolean) => void;
}

interface StaviaChatMessage {
  id: string;
  role: "USUARIO" | "STAVIA";
  text: string;
  createdAt: string;
  confidence?: StaviaConfidence;
  sourcesCount?: number;
}

interface LastContext {
  obraId: string | null;
  rdoId: string | null;
}

interface StaviaContextOption {
  obraId: string;
  rdoId: string;
  title: string;
  subtitle: string;
  meta: string;
  searchText: string;
  updatedAt: string | null;
}

const CHAT_STORAGE_KEY = "cortex:stavia:chat:operacional";
const LAST_CONTEXT_KEY = "cortex:stavia:last-context";
const LAUNCHER_DRAG_THRESHOLD = 6;

const SUGGESTIONS = [
  "Quem está trabalhando nesta obra?",
  "Qual obra está acontecendo na cidade X?",
  "Quantos RDOs dessa obra eu tenho?",
  "Quais equipamentos foram usados neste RDO?",
  "Qual foi o turno registrado?",
  "Quais materiais foram aplicados?",
  "Essa obra tem risco de desvio de custo?",
  "Quando esses dados foram atualizados?",
];

function createLocalId(): string {
  if ("randomUUID" in crypto) {
    return crypto.randomUUID();
  }

  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function loadChatMessages(): StaviaChatMessage[] {
  try {
    const raw = window.localStorage.getItem(CHAT_STORAGE_KEY);
    if (!raw) {
      return [];
    }

    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [];
    }

    return parsed.filter(
      (message): message is StaviaChatMessage =>
        typeof message === "object" &&
        message !== null &&
        "id" in message &&
        "role" in message &&
        "text" in message &&
        "createdAt" in message,
    );
  } catch {
    return [];
  }
}

function persistChatMessages(messages: StaviaChatMessage[]) {
  try {
    if (messages.length === 0) {
      window.localStorage.removeItem(CHAT_STORAGE_KEY);
      return;
    }

    window.localStorage.setItem(
      CHAT_STORAGE_KEY,
      JSON.stringify(messages.slice(-50)),
    );
  } catch {
    // Histórico local é auxiliar.
  }
}

function loadLastContext(): LastContext {
  try {
    const raw = window.localStorage.getItem(LAST_CONTEXT_KEY);
    if (!raw) {
      return { obraId: null, rdoId: null };
    }

    const parsed = JSON.parse(raw) as Partial<LastContext>;
    return {
      obraId:
        typeof parsed.obraId === "string" ? parsed.obraId : null,
      rdoId:
        typeof parsed.rdoId === "string" ? parsed.rdoId : null,
    };
  } catch {
    return { obraId: null, rdoId: null };
  }
}

function persistLastContext(context: LastContext) {
  try {
    window.localStorage.setItem(
      LAST_CONTEXT_KEY,
      JSON.stringify(context),
    );
  } catch {
    // Contexto é apenas um acelerador local.
  }
}

function confidenceLabel(confidence: StaviaConfidence): string {
  switch (confidence) {
    case "ALTA":
      return "Alta";
    case "MEDIA":
      return "Média";
    case "BAIXA":
      return "Baixa";
    case "INDETERMINADA":
      return "Indeterminada";
  }
}

function formatUpdatedAt(value: string | null | undefined): string {
  if (!value) {
    return "sem atualização local";
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

function formatChatTimestamp(value: string): string {
  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return "agora";
  }

  return new Intl.DateTimeFormat("pt-BR", {
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

function responseWantsDetails(
  response: StaviaConsultaResponse | null,
): boolean {
  return response?.answer.metadata?.modoDetalhado === true;
}

function compactParts(
  values: Array<string | null | undefined>,
): string {
  return values
    .map((value) => value?.trim())
    .filter((value): value is string => Boolean(value))
    .join(" · ");
}

function formatDateOnly(value: string | null | undefined): string {
  if (!value) {
    return "";
  }

  const date = /^\d{4}-\d{2}-\d{2}$/.test(value)
    ? new Date(`${value}T00:00:00`)
    : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
  }).format(date);
}

function obraDisplayName(
  obra: StaviaSnapshotObra | undefined,
  rdo: StaviaSnapshotRdo,
): string {
  return (
    obra?.nome?.trim() ||
    obra?.codigoInterno?.trim() ||
    obra?.codigoCw?.trim() ||
    obra?.codigoContrato?.trim() ||
    rdo.cidade?.trim() ||
    rdo.contrato?.trim() ||
    "Obra sem nome"
  );
}

function buildContextOptions(
  snapshot: StaviaSnapshot | null,
): StaviaContextOption[] {
  if (!snapshot) {
    return [];
  }

  const obraById = new Map(
    snapshot.obras.map((obra) => [obra.id, obra]),
  );

  return snapshot.rdos
    .map((rdo) => {
      const obra = obraById.get(rdo.obraId);
      const obraName = obraDisplayName(obra, rdo);
      const rdoLabel = rdo.numeroRdo?.trim()
        ? `RDO ${rdo.numeroRdo.trim()}`
        : "RDO sem número";
      const city = rdo.cidade ?? obra?.cidade ?? null;
      const contract =
        rdo.contrato ??
        obra?.codigoContrato ??
        obra?.codigoCw ??
        null;
      const road = rdo.rodovia ?? obra?.rodovia ?? null;
      const date = formatDateOnly(rdo.dataRdo);
      const subtitle = compactParts([city, contract, road, date]);
      const meta = compactParts([
        obra?.cliente,
        rdo.turno ? `Turno ${rdo.turno}` : null,
        rdo.status,
      ]);

      return {
        obraId: rdo.obraId,
        rdoId: rdo.id,
        title: `${obraName} · ${rdoLabel}`,
        subtitle: subtitle || "RDO registrado no dispositivo",
        meta,
        searchText: compactParts([
          obraName,
          rdoLabel,
          city,
          contract,
          road,
          obra?.codigoCw,
          obra?.codigoContrato,
          obra?.codigoInterno,
          rdo.id,
          rdo.obraId,
        ]).toLocaleLowerCase("pt-BR"),
        updatedAt: rdo.updatedAt ?? obra?.updatedAt ?? null,
      };
    })
    .sort((left, right) =>
      (right.updatedAt ?? "").localeCompare(left.updatedAt ?? ""),
    );
}

function filterContextOptions(
  options: StaviaContextOption[],
  query: string,
): StaviaContextOption[] {
  const normalizedQuery = query.trim().toLocaleLowerCase("pt-BR");
  if (!normalizedQuery) {
    return options.slice(0, 60);
  }

  return options
    .filter((option) => option.searchText.includes(normalizedQuery))
    .slice(0, 60);
}

export function StaviaPanel({
  initialObraId = "",
  initialRdoId = "",
  onBack,
  variant = "page",
  isOpen,
  onOpenChange,
}: StaviaPanelProps) {
  const storedContext = useMemo(() => loadLastContext(), []);
  const [activeObraId, setActiveObraId] = useState(
    initialObraId || storedContext.obraId || "",
  );
  const [activeRdoId, setActiveRdoId] = useState(
    initialRdoId || storedContext.rdoId || "",
  );
  const [contextHint, setContextHint] = useState("");
  const [pergunta, setPergunta] = useState("");
  const [isContextSelectorOpen, setIsContextSelectorOpen] =
    useState(false);
  const [contextSelectorQuery, setContextSelectorQuery] =
    useState("");
  const [isOnline, setIsOnline] = useState(() => navigator.onLine);
  const [isLoading, setIsLoading] = useState(false);
  const [isSyncingSnapshot, setIsSyncingSnapshot] = useState(false);
  const [isUploadingContext, setIsUploadingContext] = useState(false);
  const [error, setError] = useState("");
  const [statusMessage, setStatusMessage] = useState("");
  const [contextDescription, setContextDescription] = useState("");
  const [contextFile, setContextFile] = useState<File | null>(null);
  const [snapshot, setSnapshot] = useState<StaviaSnapshot | null>(null);
  const [response, setResponse] =
    useState<StaviaConsultaResponse | null>(null);
  const [chatMessages, setChatMessages] =
    useState<StaviaChatMessage[]>(loadChatMessages);
  const [isInternalFloatingOpen, setIsInternalFloatingOpen] =
    useState(false);
  const [launcherOffset, setLauncherOffset] = useState({
    x: 0,
    y: 0,
  });
  const [isLauncherDragging, setIsLauncherDragging] =
    useState(false);
  const launcherDragRef = useRef<{
    pointerId: number;
    startX: number;
    startY: number;
    didDrag: boolean;
  } | null>(null);
  const suppressLauncherClickRef = useRef(false);
  const formRef = useRef<HTMLFormElement>(null);
  const chatThreadRef = useRef<HTMLDivElement>(null);
  const isFloating = variant === "floating";
  const isFloatingOpen =
    isOpen ?? isInternalFloatingOpen;

  const lastUpdatedAt =
    snapshot?.metadata.localSyncedAt ??
    snapshot?.metadata.databaseUpdatedAt ??
    snapshot?.metadata.generatedAt ??
    null;
  const contextOptions = useMemo(
    () => buildContextOptions(snapshot),
    [snapshot],
  );
  const filteredContextOptions = useMemo(
    () =>
      filterContextOptions(
        contextOptions,
        contextSelectorQuery,
      ),
    [contextOptions, contextSelectorQuery],
  );
  const activeContextOption = useMemo(
    () =>
      contextOptions.find(
        (option) =>
          option.rdoId === activeRdoId ||
          (!activeRdoId && option.obraId === activeObraId),
      ) ?? null,
    [activeObraId, activeRdoId, contextOptions],
  );
  const contextWorksiteCount = useMemo(
    () => new Set(contextOptions.map((option) => option.obraId)).size,
    [contextOptions],
  );

  useEffect(() => {
    function handleOnline() {
      setIsOnline(true);
    }

    function handleOffline() {
      setIsOnline(false);
    }

    window.addEventListener("online", handleOnline);
    window.addEventListener("offline", handleOffline);

    return () => {
      window.removeEventListener("online", handleOnline);
      window.removeEventListener("offline", handleOffline);
    };
  }, []);

  useEffect(() => {
    let cancelled = false;

    getBestAvailableStaviaSnapshot()
      .then((loadedSnapshot) => {
        if (!cancelled) {
          setSnapshot(loadedSnapshot);
        }
      })
      .catch((snapshotError: unknown) => {
        if (!cancelled) {
          setError(
            snapshotError instanceof Error
              ? snapshotError.message
              : "Não foi possível carregar a base local da Stav.IA.",
          );
        }
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    persistLastContext({
      obraId: activeObraId.trim() || null,
      rdoId: activeRdoId.trim() || null,
    });
  }, [activeObraId, activeRdoId]);

  useEffect(() => {
    if (chatMessages.length === 0) {
      return;
    }

    window.requestAnimationFrame(() => {
      const chatThread = chatThreadRef.current;

      if (!chatThread) {
        return;
      }

      chatThread.scrollTo({
        top: chatThread.scrollHeight,
        behavior: "smooth",
      });
    });
  }, [chatMessages.length, isLoading]);

  useEffect(() => {
    if (!isFloating || !isFloatingOpen) {
      return;
    }

    const previousOverflow = document.body.style.overflow;

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape" && !isContextSelectorOpen) {
        setIsInternalFloatingOpen(false);
        onOpenChange?.(false);
      }
    }

    document.body.style.overflow = "hidden";
    window.addEventListener("keydown", handleKeyDown);

    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [isContextSelectorOpen, isFloating, isFloatingOpen, onOpenChange]);

  useEffect(() => {
    if (!isContextSelectorOpen) {
      return;
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setIsContextSelectorOpen(false);
      }
    }

    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [isContextSelectorOpen]);

  function appendMessage(message: StaviaChatMessage) {
    setChatMessages((current) => {
      const next = [...current, message];
      persistChatMessages(next);
      return next;
    });
  }

  function setFloatingOpen(nextOpen: boolean) {
    setIsInternalFloatingOpen(nextOpen);
    onOpenChange?.(nextOpen);
  }

  function handlePanelClose() {
    if (isFloating) {
      setFloatingOpen(false);
      return;
    }

    onBack?.();
  }

  function handleLauncherPointerDown(
    event: ReactPointerEvent<HTMLButtonElement>,
  ) {
    if (event.button !== 0) {
      return;
    }

    launcherDragRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      didDrag: false,
    };
    setIsLauncherDragging(true);
    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function handleLauncherPointerMove(
    event: ReactPointerEvent<HTMLButtonElement>,
  ) {
    const drag = launcherDragRef.current;

    if (!drag || drag.pointerId !== event.pointerId) {
      return;
    }

    const x = event.clientX - drag.startX;
    const y = event.clientY - drag.startY;

    if (Math.hypot(x, y) > LAUNCHER_DRAG_THRESHOLD) {
      drag.didDrag = true;
    }

    setLauncherOffset({ x, y });
  }

  function finishLauncherDrag(
    event: ReactPointerEvent<HTMLButtonElement>,
  ) {
    const drag = launcherDragRef.current;

    if (drag?.pointerId === event.pointerId) {
      suppressLauncherClickRef.current = drag.didDrag;

      if (event.currentTarget.hasPointerCapture(event.pointerId)) {
        event.currentTarget.releasePointerCapture(event.pointerId);
      }
    }

    launcherDragRef.current = null;
    setIsLauncherDragging(false);
    setLauncherOffset({ x: 0, y: 0 });
  }

  function handleLauncherClick() {
    if (suppressLauncherClickRef.current) {
      suppressLauncherClickRef.current = false;
      return;
    }

    setFloatingOpen(true);
  }

  async function refreshSnapshot(): Promise<StaviaSnapshot | null> {
    if (!isOnline) {
      setStatusMessage(
        "Modo offline — respondendo com dados salvos neste dispositivo.",
      );
      return snapshot;
    }

    setIsSyncingSnapshot(true);
    setError("");
    setStatusMessage("");

    try {
      const remoteSnapshot = await baixarSnapshotStavia();
      const savedSnapshot = await saveStaviaSnapshot(remoteSnapshot);
      const mergedSnapshot = await getBestAvailableStaviaSnapshot();

      setSnapshot(mergedSnapshot ?? savedSnapshot);
      setStatusMessage(
        "Base da Stav.IA atualizada neste dispositivo.",
      );

      return mergedSnapshot ?? savedSnapshot;
    } catch (syncError: unknown) {
      const localSnapshot = await getBestAvailableStaviaSnapshot();

      if (localSnapshot) {
        setSnapshot(localSnapshot);
        setStatusMessage(
          syncError instanceof StaviaApiError &&
            syncError.status === 404
            ? "Snapshot da API não disponível nesta instância. Base local recomposta com RDOs salvos neste dispositivo."
            : "Não consegui atualizar pela API agora. Base local mantida com os dados salvos neste dispositivo.",
        );
        return localSnapshot;
      }

      setError(
        syncError instanceof Error
          ? syncError.message
          : "Não foi possível atualizar a base da Stav.IA.",
      );
      return null;
    } finally {
      setIsSyncingSnapshot(false);
    }
  }

  async function answerQuestion(
    questionText: string,
  ): Promise<StaviaConsultaResponse> {
    const latestSnapshot =
      snapshot ?? (await getBestAvailableStaviaSnapshot());
    const selectedContextText = contextHint.trim() || null;

    if (latestSnapshot) {
      const localResponse = responderComSnapshotStavia({
        snapshot: latestSnapshot,
        pergunta: questionText,
        contextoSelecionado: selectedContextText,
        context: {
          activeObraId: activeObraId.trim() || null,
          activeRdoId: activeRdoId.trim() || null,
          lastObraId: storedContext.obraId,
          lastRdoId: storedContext.rdoId,
        },
        isOnline,
      });

      if (localResponse) {
        return localResponse;
      }
    }

    if (!isOnline) {
      throw new Error(
        "Estou offline e não encontrei essa resposta no snapshot salvo. Atualize a base quando a internet voltar.",
      );
    }

    return consultarStavia({
      pergunta: questionText,
      usuarioId: "frontend-local",
      obraId: activeObraId.trim() || null,
      rdoId: activeRdoId.trim() || null,
      contextoSelecionado: selectedContextText,
      ultimoObraId: storedContext.obraId,
      ultimoRdoId: storedContext.rdoId,
    });
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const questionText = pergunta.trim();
    setError("");
    setStatusMessage("");
    setResponse(null);

    if (!questionText) {
      setError("Digite uma pergunta para a Stav.IA.");
      return;
    }

    appendMessage({
      id: createLocalId(),
      role: "USUARIO",
      text: questionText,
      createdAt: new Date().toISOString(),
    });

    setPergunta("");
    setIsLoading(true);

    try {
      const result = await answerQuestion(questionText);
      setResponse(result);

      appendMessage({
        id: createLocalId(),
        role: "STAVIA",
        text: result.answer.answer,
        createdAt: new Date().toISOString(),
        confidence: result.answer.confidence,
        sourcesCount: result.answer.sources.length,
      });
    } catch (requestError: unknown) {
      setError(
        requestError instanceof Error
          ? requestError.message
          : "Não foi possível concluir a consulta.",
      );
    } finally {
      setIsLoading(false);
    }
  }

  function handleSuggestionClick(suggestion: string) {
    setPergunta(suggestion);
    setError("");
    setStatusMessage("");
  }

  function handleQuestionKeyDown(
    event: ReactKeyboardEvent<HTMLTextAreaElement>,
  ) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      formRef.current?.requestSubmit();
    }
  }

  function handleOpenContextSelector() {
    setError("");
    setStatusMessage("");
    setIsContextSelectorOpen(true);
  }

  function handleContextOptionSelect(option: StaviaContextOption) {
    setActiveObraId(option.obraId);
    setActiveRdoId(option.rdoId);
    setContextHint(
      compactParts([
        option.title,
        option.subtitle,
        option.meta,
      ]),
    );
    persistLastContext({
      obraId: option.obraId,
      rdoId: option.rdoId,
    });
    setStatusMessage(
      `Contexto operacional selecionado: ${option.title}.`,
    );
    setIsContextSelectorOpen(false);
    setContextSelectorQuery("");
  }

  function handleClearChat() {
    persistChatMessages([]);
    setChatMessages([]);
    setResponse(null);
  }

  function handleContextFileChange(
    event: ChangeEvent<HTMLInputElement>,
  ) {
    setContextFile(event.target.files?.[0] ?? null);
    setStatusMessage("");
    setError("");
  }

  async function handleContextUpload() {
    setError("");
    setStatusMessage("");

    const obraId = activeObraId.trim() || storedContext.obraId;

    if (!obraId) {
      setError(
        "Selecione ou informe uma obra antes de adicionar contexto.",
      );
      return;
    }

    if (!contextFile) {
      setError("Selecione um arquivo para adicionar ao contexto.");
      return;
    }

    setIsUploadingContext(true);

    try {
      const uploaded = await adicionarContextoStavia({
        obraId,
        arquivo: contextFile,
        usuarioId: "frontend-local",
        descricao: contextDescription.trim() || undefined,
      });

      setStatusMessage(
        `Contexto "${uploaded.nomeArquivo}" anexado à Stav.IA.`,
      );
      setContextFile(null);
      setContextDescription("");
    } catch (requestError: unknown) {
      setError(
        requestError instanceof Error
          ? requestError.message
          : "Não foi possível anexar o contexto.",
      );
    } finally {
      setIsUploadingContext(false);
    }
  }

  const panelContent = (
    <>
      <section className="stavia-hero-panel">
        <header className="stavia-hero-header">
          <button
            type="button"
            className="stavia-back-button"
            onClick={handlePanelClose}
            disabled={isLoading || isUploadingContext}
            aria-label={isFloating ? "Fechar Stav.IA" : "Voltar"}
          >
            {isFloating ? (
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth={2.2}
                strokeLinecap="round"
                aria-hidden="true"
              >
                <path d="M6 6l12 12M18 6L6 18" />
              </svg>
            ) : (
              "Voltar"
            )}
          </button>
        </header>

        <div className="stavia-intro-row">
          <div className="stavia-logo-card">
            <img src="/stavia-logo.png" alt="STAV.IA" />
          </div>

          <div className="stavia-intro-wrap">
            <span className="stavia-info-dot" aria-hidden="true">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth={2}
                strokeLinecap="round"
              >
                <circle cx="12" cy="12" r="9" />
                <path d="M12 11v5M12 7.5v.01" />
              </svg>
            </span>

            <p className="stavia-intro-copy">
              A Stav.IA é a assistente operacional do Córtex para
              consultar RDOs, obras, equipes, equipamentos, materiais,
              programação, sincronização e PDOC com os dados salvos no
              sistema.
            </p>
          </div>
        </div>

        {!isOnline && (
          <div className="stavia-offline-banner">
            Modo offline — respondendo com dados salvos neste
            dispositivo.
          </div>
        )}

        <p className="stavia-suggestions-title">
          Dicas do que perguntar
        </p>

        <div
          className="stavia-suggestion-crawl"
          aria-label="Perguntas sugeridas"
        >
          <div className="stavia-suggestion-crawl-track">
            {SUGGESTIONS.map((suggestion, index) => (
              <button
                key={suggestion}
                type="button"
                style={
                  {
                    "--stavia-tip-index": index,
                  } as CSSProperties
                }
                onClick={() => handleSuggestionClick(suggestion)}
                disabled={isLoading}
              >
                {suggestion}
              </button>
            ))}
          </div>
        </div>

        <div className="stavia-tip-box">
          <strong>Você sabia?</strong>
          <span>
            O PDOC aparece como indicador heurístico quando o modelo
            ainda não está calibrado.
          </span>
        </div>

        <div className="stavia-selected-context">
          <div>
            <span>Contexto operacional</span>
            <strong>
              {activeContextOption
                ? activeContextOption.title
                : "Nenhuma obra/RDO selecionado"}
            </strong>
            <small>
              {activeContextOption
                ? `${activeContextOption.subtitle}. IDs ontológicos preservados na consulta.`
                : `${contextWorksiteCount} obra(s) e ${contextOptions.length} RDO(s) disponíveis no snapshot local.`}
            </small>
          </div>

          <button
            type="button"
            className="stavia-secondary-action"
            onClick={handleOpenContextSelector}
            disabled={isLoading || isUploadingContext}
          >
            Selecionar obra/RDO
          </button>
        </div>

        <form
          ref={formRef}
          className="stavia-query-box"
          onSubmit={handleSubmit}
        >
          <div className="stavia-prompt-meta">
            <span
              className={`stavia-network-pill ${
                isOnline ? "is-online" : "is-offline"
              }`}
            >
              {isOnline ? "Online" : "Offline"}
            </span>

            <span className="stavia-meta-time">
              Última atualização em {formatUpdatedAt(lastUpdatedAt)}
            </span>

            <button
              type="button"
              className="stavia-refresh-button"
              onClick={() => {
                void refreshSnapshot();
              }}
              disabled={isSyncingSnapshot || isLoading}
            >
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth={2}
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <path d="M21 12a9 9 0 1 1-2.64-6.36M21 4v4h-4" />
              </svg>
              {isSyncingSnapshot ? "Atualizando..." : "Atualizar base"}
            </button>
          </div>

          {contextFile && (
            <div className="stavia-attach-tray">
              <span className="stavia-attach-chip">
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={2}
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <path d="M21 15V8a2 2 0 0 0-1-1.7l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.7l7 4a2 2 0 0 0 2 0l3-1.7" />
                </svg>
                <span>{contextFile.name}</span>
              </span>

              <input
                type="text"
                value={contextDescription}
                onChange={(event) => {
                  setContextDescription(event.target.value);
                }}
                placeholder="Descrição do anexo (opcional)"
                disabled={isUploadingContext}
              />

              <button
                type="button"
                className="stavia-primary-action compact"
                onClick={() => {
                  void handleContextUpload();
                }}
                disabled={isUploadingContext || isLoading}
              >
                {isUploadingContext ? "Anexando..." : "Anexar"}
              </button>

              <button
                type="button"
                className="stavia-attach-remove"
                onClick={() => {
                  setContextFile(null);
                  setContextDescription("");
                }}
                aria-label="Remover anexo"
              >
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={2.2}
                  strokeLinecap="round"
                  aria-hidden="true"
                >
                  <path d="M6 6l12 12M18 6L6 18" />
                </svg>
              </button>
            </div>
          )}

          <div className="stavia-question-shell">
            <label className="stavia-plus-button" title="Anexar arquivo">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth={2.2}
                strokeLinecap="round"
                aria-hidden="true"
              >
                <path d="M12 5v14M5 12h14" />
              </svg>
              <input
                type="file"
                aria-label="Anexar arquivo de contexto"
                accept=".pdf,image/*,.txt,.md,.csv,.json"
                onChange={handleContextFileChange}
                disabled={isLoading || isUploadingContext}
              />
            </label>

            <textarea
              value={pergunta}
              onChange={(event) => {
                setPergunta(event.target.value);
                setError("");
                setStatusMessage("");
              }}
              onKeyDown={handleQuestionKeyDown}
              rows={1}
              disabled={isLoading || isUploadingContext}
              placeholder="Pergunte para a inteligência artificial Stav.IA..."
            />

            <button
              type="submit"
              className="stavia-send-button"
              disabled={isLoading || !pergunta.trim()}
              aria-label="Perguntar"
              title="Perguntar"
            >
              {isLoading ? (
                <span className="stavia-spinner" aria-hidden="true" />
              ) : (
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={2.2}
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <path d="M12 19V5M5 12l7-7 7 7" />
                </svg>
              )}
            </button>
          </div>

          <div className="stavia-context-hint">
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={2}
              strokeLinecap="round"
              aria-hidden="true"
            >
              <path d="M3 7h18M3 12h18M3 17h12" />
            </svg>
            <input
              type="text"
              value={contextHint}
              onChange={(event) => setContextHint(event.target.value)}
              disabled={isLoading || isUploadingContext}
              placeholder="Contexto adicional: cidade, RDO, contrato, CW ou obra"
            />
          </div>
        </form>

        {(error || statusMessage) && (
          <div
            className={`stavia-message ${
              error ? "is-error" : "is-success"
            }`}
            role={error ? "alert" : "status"}
          >
            {error || statusMessage}
          </div>
        )}

      {isContextSelectorOpen && (
        <div
          className="stavia-context-modal-backdrop"
          role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) {
              setIsContextSelectorOpen(false);
            }
          }}
        >
          <section
            className="stavia-context-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="stavia-context-modal-title"
          >
            <header className="stavia-context-modal-header">
              <div>
                <p>Selecionar contexto</p>
                <h2 id="stavia-context-modal-title">
                  Obras e RDOs registrados
                </h2>
                <span>
                  A seleção preserva `obraId` e `rdoId` rastreáveis
                  na ontologia operacional.
                </span>
              </div>

              <button
                type="button"
                className="stavia-modal-close"
                onClick={() => {
                  setIsContextSelectorOpen(false);
                }}
                aria-label="Fechar seletor"
              >
                ×
              </button>
            </header>

            <div className="stavia-context-modal-tools">
              <input
                type="search"
                value={contextSelectorQuery}
                onChange={(event) => {
                  setContextSelectorQuery(event.target.value);
                }}
                placeholder="Buscar por obra, RDO, cidade, contrato, CW ou trecho"
                autoFocus
              />

              <button
                type="button"
                className="stavia-secondary-action compact"
                onClick={() => {
                  void refreshSnapshot();
                }}
                disabled={isSyncingSnapshot || !isOnline}
              >
                {isSyncingSnapshot ? "Atualizando..." : "Atualizar"}
              </button>
            </div>

            <div className="stavia-context-modal-summary">
              {contextWorksiteCount} obra(s) · {contextOptions.length} RDO(s)
              no snapshot local
            </div>

            <div className="stavia-context-option-list">
              {filteredContextOptions.length === 0 ? (
                <div className="stavia-context-empty">
                  {contextOptions.length === 0
                    ? "Nenhum RDO registrado no snapshot local. Atualize a base quando estiver online."
                    : "Nenhum RDO encontrado para essa busca."}
                </div>
              ) : (
                filteredContextOptions.map((option) => {
                  const isSelected =
                    option.rdoId === activeRdoId ||
                    (!activeRdoId && option.obraId === activeObraId);

                  return (
                    <button
                      key={`${option.obraId}:${option.rdoId}`}
                      type="button"
                      className={`stavia-context-option ${
                        isSelected ? "is-selected" : ""
                      }`}
                      onClick={() => handleContextOptionSelect(option)}
                    >
                      <span>
                        <strong>{option.title}</strong>
                        <em>{option.subtitle}</em>
                        {option.meta && <small>{option.meta}</small>}
                      </span>

                      <code>
                        obraId={option.obraId}
                        {"\n"}
                        rdoId={option.rdoId}
                      </code>
                    </button>
                  );
                })
              )}
            </div>
          </section>
        </div>
      )}

      <section className="stavia-workspace">
        <div className="stavia-chat-panel">
          <div className="stavia-chat-header">
            <div>
              <p>Conversa</p>
              <h2>Histórico local</h2>
            </div>

            <button
              type="button"
              className="stavia-secondary-action compact"
              onClick={handleClearChat}
              disabled={chatMessages.length === 0}
            >
              Limpar
            </button>
          </div>

          {chatMessages.length === 0 ? (
            <p className="stavia-empty-chat">
              Faça uma pergunta para iniciar.
            </p>
          ) : (
            <div
              ref={chatThreadRef}
              className="stavia-chat-thread"
              aria-live="polite"
            >
              {chatMessages.map((message) => (
                <article
                  key={message.id}
                  className={`stavia-chat-message stavia-chat-message--${message.role.toLowerCase()}`}
                >
                  <div>
                    <strong>
                      {message.role === "USUARIO" ? "Você" : "Stav.IA"}
                    </strong>
                    <span>{formatChatTimestamp(message.createdAt)}</span>
                  </div>

                  <p>{message.text}</p>

                  {message.role === "STAVIA" && message.confidence && (
                    <small>
                      Confiança {confidenceLabel(message.confidence)}
                      {message.sourcesCount
                        ? ` · ${message.sourcesCount} fonte(s)`
                        : ""}
                    </small>
                  )}
                </article>
              ))}
            </div>
          )}
        </div>

        {response && (
          <aside className="stavia-answer-panel" aria-live="polite">
            <div className="stavia-answer-header">
              <span>Resposta</span>
              <strong>{confidenceLabel(response.answer.confidence)}</strong>
            </div>

            <p className="stavia-answer-text">{response.answer.answer}</p>

            {response.answer.warnings.length > 0 && (
              <div className="stavia-warning-box">
                {response.answer.warnings.map((warning) => (
                  <p key={warning}>{warning}</p>
                ))}
              </div>
            )}

            {responseWantsDetails(response) && (
              <div className="stavia-sources">
                <h3>Evidências</h3>

                {response.answer.sources.length === 0 ? (
                  <p>Nenhuma evidência detalhada disponível.</p>
                ) : (
                  response.answer.sources.map((source) => (
                    <details
                      key={`${source.type}:${source.id}`}
                      className="stavia-source"
                    >
                      <summary>
                        <span>{source.summary}</span>
                        <small>{formatUpdatedAt(source.updatedAt)}</small>
                      </summary>

                      <pre>
                        {JSON.stringify(source.attributes, null, 2)}
                      </pre>
                    </details>
                  ))
                )}
              </div>
            )}
          </aside>
        )}
      </section>
      </section>
    </>
  );

  const launcherStyle = {
    "--stavia-launcher-x": `${launcherOffset.x}px`,
    "--stavia-launcher-y": `${launcherOffset.y}px`,
  } as CSSProperties;

  if (isFloating && !isFloatingOpen) {
    return (
      <button
        type="button"
        className={`stavia-launcher ${
          isLauncherDragging ? "is-dragging" : ""
        }`}
        style={launcherStyle}
        onPointerDown={handleLauncherPointerDown}
        onPointerMove={handleLauncherPointerMove}
        onPointerUp={finishLauncherDrag}
        onPointerCancel={finishLauncherDrag}
        onClick={handleLauncherClick}
        draggable={false}
        aria-label="Abrir Stav.IA"
      >
        <img src="/stavia-logo-white.png" alt="" draggable={false} />
      </button>
    );
  }

  if (isFloating) {
    return (
      <div
        className="stavia-floating-overlay"
        role="presentation"
        onMouseDown={(event) => {
          if (event.target === event.currentTarget) {
            setFloatingOpen(false);
          }
        }}
      >
        <aside
          className="stavia-floating-card"
          role="dialog"
          aria-modal="true"
          aria-label="Stav.IA"
        >
          <div className="stavia-page stavia-page--floating">
            {panelContent}
          </div>
        </aside>
      </div>
    );
  }

  return (
    <main className="stavia-page">
      {panelContent}
    </main>
  );
}
