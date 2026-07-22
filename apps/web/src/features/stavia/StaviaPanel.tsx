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
  StaviaApiError,
} from "./staviaApi";
import {
  getBestAvailableStaviaSnapshot,
  saveStaviaSnapshot,
} from "./staviaSnapshotStorage";
import { answerStaviaPanelQuestion } from "./staviaPanelAnswer";
import {
  mustResetStaviaChatHistory,
  STAVIA_CHAT_MEMORY_POLICY_VERSION,
} from "./staviaChatMemoryPolicy";
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
const CHAT_MEMORY_POLICY_KEY =
  "cortex:stavia:chat:memory-policy";
const LAST_CONTEXT_KEY = "cortex:stavia:last-context";
const LAUNCHER_DRAG_THRESHOLD = 6;

const SUGGESTIONS = [
  "Quem está trabalhando nesta obra?",
  "Quais equipamentos foram usados neste RDO?",
  "Quais materiais foram aplicados?",
  "Quantos RDOs dessa obra eu tenho?",
  "Quais tarefas foram criadas?",
];

function createLocalId(): string {
  if ("randomUUID" in crypto) {
    return crypto.randomUUID();
  }

  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function loadChatMessages(): StaviaChatMessage[] {
  try {
    if (
      mustResetStaviaChatHistory(
        window.localStorage.getItem(CHAT_MEMORY_POLICY_KEY),
      )
    ) {
      window.localStorage.removeItem(CHAT_STORAGE_KEY);
      window.localStorage.setItem(
        CHAT_MEMORY_POLICY_KEY,
        STAVIA_CHAT_MEMORY_POLICY_VERSION,
      );
      return [];
    }

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
    window.localStorage.setItem(
      CHAT_MEMORY_POLICY_KEY,
      STAVIA_CHAT_MEMORY_POLICY_VERSION,
    );

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
    return answerStaviaPanelQuestion({
      snapshot,
      questionText,
      contextHint,
      activeObraId,
      activeRdoId,
      lastContext: storedContext,
      isOnline,
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
    setIsContextSelectorOpen(false);
    setContextSelectorQuery("");
  }

  function handleClearChat() {
    persistChatMessages([]);
    setChatMessages([]);
    setResponse(null);
  }

  function handleClearContext() {
    setActiveObraId("");
    setActiveRdoId("");
    setContextHint("");
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

  const hasMessages = chatMessages.length > 0;
  const showAnswerExtras =
    response !== null &&
    (response.answer.warnings.length > 0 ||
      responseWantsDetails(response));
  const refreshTitle = lastUpdatedAt
    ? `Atualizar base — atualizada em ${formatUpdatedAt(lastUpdatedAt)}`
    : "Atualizar base local";

  const panelContent = (
    <div className="stavia-shell">
      <header className="stavia-topbar">
        <div className="stavia-brand">
          {!isFloating && onBack && (
            <button
              type="button"
              className="stavia-icon-button"
              onClick={handlePanelClose}
              disabled={isLoading || isUploadingContext}
              aria-label="Voltar"
              title="Voltar"
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
                <path d="M19 12H5M11 18l-6-6 6-6" />
              </svg>
            </button>
          )}

          <div className="stavia-logo-card">
            <img
              className="stavia-brand-logo"
              src="/stavia-logo.png"
              alt="Stav.IA"
              draggable={false}
            />
          </div>

          <span
            className={`stavia-status-dot ${
              isOnline ? "is-online" : "is-offline"
            }`}
            role="img"
            aria-label={isOnline ? "Online" : "Offline"}
            title={isOnline ? "Online" : "Offline"}
          />
        </div>

        <div className="stavia-topbar-actions">
          <button
            type="button"
            className={`stavia-icon-button ${
              isSyncingSnapshot ? "is-busy" : ""
            }`}
            onClick={() => {
              void refreshSnapshot();
            }}
            disabled={isSyncingSnapshot || isLoading}
            aria-label={refreshTitle}
            title={refreshTitle}
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
          </button>

          <button
            type="button"
            className="stavia-icon-button"
            onClick={handleClearChat}
            disabled={!hasMessages}
            aria-label="Limpar conversa"
            title="Limpar conversa"
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
              <path d="M4 7h16M9 7V4h6v3M7 7l1 13h8l1-13M10 11v5M14 11v5" />
            </svg>
          </button>

          {isFloating && (
            <button
              type="button"
              className="stavia-icon-button"
              onClick={handlePanelClose}
              aria-label="Fechar Stav.IA"
              title="Fechar"
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
          )}
        </div>
      </header>

      {!isOnline && (
        <div className="stavia-offline-note">
          Offline — respondendo com os dados salvos neste dispositivo.
        </div>
      )}

      <div
        ref={chatThreadRef}
        className="stavia-thread"
        aria-live="polite"
      >
        {!hasMessages ? (
          <div className="stavia-empty">
            <h2>Como posso ajudar na obra hoje?</h2>
            <p>
              Pergunte sobre RDOs, obras, equipes, equipamentos,
              materiais e programação.
            </p>

            <div className="stavia-chips">
              {SUGGESTIONS.map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  onClick={() => handleSuggestionClick(suggestion)}
                  disabled={isLoading}
                >
                  {suggestion}
                </button>
              ))}
            </div>

            <small>
              {lastUpdatedAt
                ? `Base local atualizada em ${formatUpdatedAt(lastUpdatedAt)}`
                : "Base local ainda não sincronizada"}
            </small>
          </div>
        ) : (
          <div className="stavia-messages">
            {chatMessages.map((message) => {
              const isUser = message.role === "USUARIO";

              return (
                <article
                  key={message.id}
                  className={`stavia-bubble ${
                    isUser
                      ? "stavia-bubble--user"
                      : "stavia-bubble--assistant"
                  }`}
                >
                  <p>{message.text}</p>

                  <footer>
                    <span>
                      {formatChatTimestamp(message.createdAt)}
                    </span>
                    {!isUser && message.confidence && (
                      <span>
                        Confiança{" "}
                        {confidenceLabel(message.confidence)}
                        {message.sourcesCount
                          ? ` · ${message.sourcesCount} fonte(s)`
                          : ""}
                      </span>
                    )}
                  </footer>
                </article>
              );
            })}

            {showAnswerExtras && response && (
              <div className="stavia-answer-extras">
                {response.answer.warnings.map((warning) => (
                  <p key={warning} className="stavia-warning">
                    {warning}
                  </p>
                ))}

                {responseWantsDetails(response) && (
                  <div className="stavia-evidence">
                    {response.answer.sources.length === 0 ? (
                      <p className="stavia-evidence-empty">
                        Nenhuma evidência detalhada disponível.
                      </p>
                    ) : (
                      response.answer.sources.map((source) => (
                        <details
                          key={`${source.type}:${source.id}`}
                          className="stavia-source"
                        >
                          <summary>
                            <span>{source.summary}</span>
                            <small>
                              {formatUpdatedAt(source.updatedAt)}
                            </small>
                          </summary>

                          <pre>
                            {JSON.stringify(
                              source.attributes,
                              null,
                              2,
                            )}
                          </pre>
                        </details>
                      ))
                    )}
                  </div>
                )}
              </div>
            )}

            {isLoading && (
              <div
                className="stavia-thinking"
                role="status"
                aria-label="Consultando a base"
              >
                <span />
                <span />
                <span />
              </div>
            )}
          </div>
        )}
      </div>

      {(error || statusMessage) && (
        <p
          className={`stavia-note ${error ? "is-error" : "is-success"}`}
          role={error ? "alert" : "status"}
        >
          {error || statusMessage}
        </p>
      )}

      <footer className="stavia-composer-area">
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
                <path d="M21.4 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" />
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
              className="stavia-attach-send"
              onClick={() => {
                void handleContextUpload();
              }}
              disabled={isUploadingContext || isLoading}
            >
              {isUploadingContext ? "Anexando..." : "Anexar"}
            </button>

            <button
              type="button"
              className="stavia-dismiss-button"
              onClick={() => {
                setContextFile(null);
                setContextDescription("");
              }}
              aria-label="Remover anexo"
              title="Remover anexo"
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

        <div className="stavia-context-row">
          <button
            type="button"
            className={`stavia-context-chip ${
              activeContextOption ? "is-active" : ""
            }`}
            onClick={handleOpenContextSelector}
            disabled={isLoading || isUploadingContext}
            title={activeContextOption?.subtitle}
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
              <path d="M20 10.5c0 5.5-8 10.5-8 10.5s-8-5-8-10.5a8 8 0 1 1 16 0z" />
              <circle cx="12" cy="10.5" r="2.5" />
            </svg>
            <span>
              {activeContextOption
                ? activeContextOption.title
                : "Selecionar obra/RDO"}
            </span>
          </button>

          {activeContextOption && (
            <button
              type="button"
              className="stavia-dismiss-button"
              onClick={handleClearContext}
              disabled={isLoading || isUploadingContext}
              aria-label="Limpar contexto selecionado"
              title="Limpar contexto"
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
          )}
        </div>

        <form
          ref={formRef}
          className="stavia-composer"
          onSubmit={handleSubmit}
        >
          <label
            className="stavia-plus-button"
            title="Anexar arquivo de contexto"
          >
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
            placeholder="Pergunte à Stav.IA..."
          />

          <button
            type="submit"
            className="stavia-send-button"
            disabled={isLoading || !pergunta.trim()}
            aria-label="Enviar pergunta"
            title="Enviar"
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
        </form>
      </footer>

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
            <header>
              <h2 id="stavia-context-modal-title">
                Selecionar obra ou RDO
              </h2>

              <button
                type="button"
                className="stavia-icon-button"
                onClick={() => {
                  setIsContextSelectorOpen(false);
                }}
                aria-label="Fechar seletor"
                title="Fechar"
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
            </header>

            <input
              type="search"
              value={contextSelectorQuery}
              onChange={(event) => {
                setContextSelectorQuery(event.target.value);
              }}
              placeholder="Buscar por obra, RDO, cidade, contrato ou trecho"
              autoFocus
            />

            <p className="stavia-context-modal-summary">
              {contextWorksiteCount} obra(s) · {contextOptions.length}{" "}
              RDO(s) neste dispositivo
            </p>

            <div className="stavia-context-option-list">
              {filteredContextOptions.length === 0 ? (
                <div className="stavia-context-empty">
                  {contextOptions.length === 0
                    ? "Nenhum RDO neste dispositivo. Atualize a base quando estiver online."
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
                      <strong>{option.title}</strong>
                      <em>{option.subtitle}</em>
                      {option.meta && <small>{option.meta}</small>}
                    </button>
                  );
                })
              )}
            </div>
          </section>
        </div>
      )}
    </div>
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
          {panelContent}
        </aside>
      </div>
    );
  }

  return <main className="stavia-page">{panelContent}</main>;
}
