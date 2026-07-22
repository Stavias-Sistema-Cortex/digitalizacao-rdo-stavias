import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { useSearchParams } from "react-router-dom";

import { CortexShell } from "../../components/shell/CortexShell";
import type {
  ConversaLocalRecord,
  MensagemAnexoLocalRecord,
  ObraLocalRecord,
} from "../../lib/db/db.types";
import { listObrasLocais } from "../../lib/db/obraLocalRepository";
import { syncNow } from "../../lib/sync/syncEngine";
import { useSyncStatus } from "../../lib/sync/useSyncStatus";
import { getSession, hasOnlineSession, isAlfa } from "../auth/authSession";
import { ConversationInfoPane } from "./components/ConversationInfoPane";
import { ConversationsPane } from "./components/ConversationsPane";
import { CreateConversationDialog } from "./components/CreateConversationDialog";
import { MessageComposer } from "./components/MessageComposer";
import { MessageThread } from "./components/MessageThread";
import { messageFrom } from "./mensagensFormat";
import {
  downloadMessageAttachmentApi,
  searchMessagesApi,
} from "./mensagensApi";
import {
  refreshConversationHistory,
  refreshConversationList,
} from "./mensagensHydration";
import {
  localAttachmentBlob,
  listLocalConversationPreviews,
  listLocalConversations,
  listLocalMessages,
  MESSAGES_CHANGED_EVENT,
  queueMessage,
  retryMessage,
  searchLocalMessages,
  storeServerConversations,
  storeServerMessages,
  type MensagemComAnexos,
} from "./mensagensRepository";
import {
  activeParticipant,
  buildMessageTimeline,
  conversationName,
  conversationScope,
  type ConversationPreview,
} from "./mensagensView";
import "./MensagensPage.css";

const INFO_COLLAPSED_KEY = "cortex.ui.mensagensContextoRecolhido";

export function MensagensPage() {
  const session = getSession();
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedConversationId = searchParams.get("conversa");
  const [conversations, setConversations] = useState<ConversaLocalRecord[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [messages, setMessages] = useState<MensagemComAnexos[]>([]);
  const [previews, setPreviews] = useState<Record<string, ConversationPreview>>({});
  const [worksites, setWorksites] = useState<ObraLocalRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState("");
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [files, setFiles] = useState<File[]>([]);
  const [sending, setSending] = useState(false);
  const [search, setSearch] = useState("");
  const [searchResults, setSearchResults] = useState<MensagemComAnexos[] | null>(
    null,
  );
  const [showCreate, setShowCreate] = useState(false);
  const [mobilePane, setMobilePane] = useState<"list" | "thread" | "context">("list");
  const [contextOpen, setContextOpen] = useState(false);
  const [infoCollapsed, setInfoCollapsed] = useState(
    () => localStorage.getItem(INFO_COLLAPSED_KEY) === "1",
  );
  const [now, setNow] = useState(() => new Date());
  const frameRef = useRef<HTMLDivElement>(null);
  const [wideFrame, setWideFrame] = useState(false);
  const { snapshot } = useSyncStatus();

  const loadLocal = useCallback(async () => {
    const [localConversations, localPreviews, localWorksites] = await Promise.all([
      listLocalConversations(),
      listLocalConversationPreviews(),
      listObrasLocais(),
    ]);
    setConversations(localConversations);
    setPreviews(localPreviews);
    setWorksites(localWorksites);
      setSelectedId((current) => {
        if (
          requestedConversationId &&
          localConversations.some(
            (conversation) => conversation.id === requestedConversationId,
          )
        ) {
          return requestedConversationId;
        }
        if (current && localConversations.some((item) => item.id === current)) {
        return current;
      }
      return localConversations[0]?.id ?? null;
    });
  }, [requestedConversationId]);

  const loadMessages = useCallback(async (conversationId: string | null) => {
    setMessages(
      conversationId ? await listLocalMessages(conversationId) : [],
    );
  }, []);

  useEffect(() => {
    let cancelled = false;
    async function start() {
      try {
        await loadLocal();
        if (navigator.onLine && hasOnlineSession()) {
          await refreshConversationList();
          await loadLocal();
        }
      } catch (cause: unknown) {
        if (!cancelled) {
          setError(messageFrom(cause));
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }
    void start();
    return () => {
      cancelled = true;
    };
  }, [loadLocal]);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        await loadMessages(selectedId);
        if (selectedId && navigator.onLine && hasOnlineSession()) {
          await refreshConversationHistory(selectedId);
          await loadMessages(selectedId);
        }
      } catch (cause: unknown) {
        if (!cancelled) {
          setError(messageFrom(cause));
        }
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, [loadMessages, selectedId]);

  useEffect(() => {
    function changed() {
      void loadLocal();
      void loadMessages(selectedId);
    }
    window.addEventListener(MESSAGES_CHANGED_EVENT, changed);
    return () => window.removeEventListener(MESSAGES_CHANGED_EVENT, changed);
  }, [loadLocal, loadMessages, selectedId]);

  const selected = useMemo(
    () => conversations.find((item) => item.id === selectedId) ?? null,
    [conversations, selectedId],
  );
  const body = selectedId ? drafts[selectedId] ?? "" : "";
  const timeline = useMemo(() => buildMessageTimeline(messages), [messages]);
  const isGroup = selected ? selected.tipo !== "DIRETA" : false;

  /* Espelha o @container (min-width: 1040px) para o handler do botão de
     contexto. Viewport não serve: a sidebar do shell é redimensionável. */
  useEffect(() => {
    const el = frameRef.current;
    if (!el) return;
    const observer = new ResizeObserver(([entry]) => {
      setWideFrame(entry.contentRect.width >= 1040);
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // As legendas de run são relativas; sem este tique elas congelam.
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), 60_000);
    return () => window.clearInterval(id);
  }, []);

  async function handleRefresh() {
    if (refreshing) return;
    setRefreshing(true);
    setError("");
    try {
      if (!navigator.onLine || !hasOnlineSession()) {
        throw new Error(
          "Sem conexão ou sessão online. As mensagens locais continuam disponíveis.",
        );
      }
      const summary = await syncNow();
      await refreshConversationList();
      if (selectedId) await refreshConversationHistory(selectedId);
      await Promise.all([loadLocal(), loadMessages(selectedId)]);
      if (summary.errors > 0 || summary.conflicts > 0) {
        setError(
          `${summary.errors + summary.conflicts} item(ns) ainda precisam de nova tentativa.`,
        );
      }
    } catch (cause: unknown) {
      setError(messageFrom(cause));
    } finally {
      setRefreshing(false);
    }
  }

  async function handleSend(event: FormEvent) {
    event.preventDefault();
    if (!selectedId || sending) return;
    setSending(true);
    setError("");
    try {
      await queueMessage({ conversaId: selectedId, corpo: body, files });
      setDrafts((current) => ({ ...current, [selectedId]: "" }));
      setFiles([]);
      await loadMessages(selectedId);
      if (navigator.onLine && hasOnlineSession()) {
        void syncNow()
          .then(() => loadMessages(selectedId))
          .catch((cause: unknown) => setError(messageFrom(cause)));
      }
    } catch (cause: unknown) {
      setError(messageFrom(cause));
    } finally {
      setSending(false);
    }
  }

  async function handleSearch(event: FormEvent) {
    event.preventDefault();
    const query = search.trim();
    if (!query) {
      setSearchResults(null);
      return;
    }
    setError("");
    try {
      let results = await searchLocalMessages(query);
      if (navigator.onLine && hasOnlineSession()) {
        const serverResults = await searchMessagesApi(query);
        await storeServerMessages(serverResults);
        results = await searchLocalMessages(query);
      }
      setSearchResults(results);
    } catch (cause: unknown) {
      setError(messageFrom(cause));
    }
  }

  async function handleRetry(messageId: string) {
    setError("");
    try {
      await retryMessage(messageId);
      await loadMessages(selectedId);
      if (navigator.onLine && hasOnlineSession()) {
        await syncNow();
        await loadMessages(selectedId);
      }
    } catch (cause: unknown) {
      setError(messageFrom(cause));
    }
  }

  async function openAttachment(attachment: MensagemAnexoLocalRecord) {
    setError("");
    try {
      const blob =
        (await localAttachmentBlob(attachment.id)) ??
        (await downloadMessageAttachmentApi(attachment.id));
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = attachment.nome;
      anchor.rel = "noopener";
      anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (cause: unknown) {
      setError(messageFrom(cause));
    }
  }

  function chooseSearchResult(result: MensagemComAnexos) {
    setSelectedId(result.conversaId);
    setSearchParams({ conversa: result.conversaId });
    setSearchResults(null);
    setContextOpen(false);
    setMobilePane("thread");
  }

  function chooseConversation(id: string) {
    setSelectedId(id);
    setSearchParams({ conversa: id });
    setContextOpen(false);
    setMobilePane("thread");
  }

  function clearSearch() {
    setSearch("");
    setSearchResults(null);
  }

  function openContext() {
    setContextOpen(true);
    setMobilePane("context");
  }

  /**
   * Acima de 1040px de frame o contexto é coluna em fluxo e o botão recolhe ou
   * mostra essa coluna; abaixo disso ele abre a gaveta. Conflacionar os dois
   * fazia o botão "Ver contexto" esconder justamente o painel que abre.
   */
  function toggleInfo() {
    if (wideFrame) {
      setInfoCollapsed((current) => {
        const next = !current;
        localStorage.setItem(INFO_COLLAPSED_KEY, next ? "1" : "0");
        return next;
      });
      return;
    }
    openContext();
  }

  return (
    <CortexShell
      active="mensagens"
      onRefresh={() => void handleRefresh()}
      isRefreshing={refreshing}
    >
      <main className="mensagens-page">
        <header className="mensagens-header">
          <div><h1>Mensagens</h1><p>{conversations.length} conversas autorizadas</p></div>
          <button
            type="button"
            className="mensagens-primary"
            onClick={() => setShowCreate(true)}
            disabled={!navigator.onLine || !hasOnlineSession()}
          >
            Nova conversa
          </button>
        </header>

        {error ? (
          <div className="mensagens-alert" role="alert">
            <span>{error}</span>
            <button type="button" onClick={() => setError("")}>
              Fechar
            </button>
          </div>
        ) : null}

        <div className="mensagens-frame" ref={frameRef}>
        <section
          className={`mensagens-workspace mensagens-workspace--${mobilePane}${
            contextOpen ? " mensagens-workspace--drawer-open" : ""
          }${infoCollapsed ? " mensagens-workspace--info-hidden" : ""}`}
          aria-label="Mensagens"
        >
          <ConversationsPane
            loading={loading}
            conversations={conversations}
            previews={previews}
            selectedId={selectedId}
            currentUserId={session?.colaboradorId ?? ""}
            isOnline={navigator.onLine}
            now={now}
            search={search}
            searchResults={searchResults}
            onSearchChange={setSearch}
            onSearchSubmit={handleSearch}
            onCloseSearch={clearSearch}
            onSelect={chooseConversation}
            onChooseSearchResult={chooseSearchResult}
          />

          <MessageThread
            conversation={selected}
            title={selected ? conversationName(selected, session?.colaboradorId) : ""}
            scope={selected ? conversationScope(selected) : ""}
            participantCount={
              selected ? selected.participantes.filter(activeParticipant).length : 0
            }
            timeline={timeline}
            hasMessages={messages.length > 0}
            currentUserId={session?.colaboradorId ?? ""}
            isGroup={isGroup}
            now={now}
            infoVisible={wideFrame ? !infoCollapsed : contextOpen}
            onBack={() => setMobilePane("list")}
            onOpenInfo={toggleInfo}
            onOpenAttachment={openAttachment}
            onRetry={handleRetry}
            composer={
              <MessageComposer
                value={body}
                files={files}
                sending={sending}
                isOnline={navigator.onLine}
                onChange={(value) =>
                  selectedId &&
                  setDrafts((current) => ({ ...current, [selectedId]: value }))
                }
                onFilesChange={setFiles}
                onRemoveFile={(index) =>
                  setFiles((current) =>
                    current.filter((_, itemIndex) => itemIndex !== index),
                  )
                }
                onSubmit={handleSend}
              />
            }
          />

          {contextOpen ? (
            <button
              type="button"
              className="mensagens-drawer-backdrop"
              aria-label="Fechar contexto"
              onClick={() => setContextOpen(false)}
            />
          ) : null}
          <ConversationInfoPane
            conversation={selected}
            title={selected ? conversationName(selected, session?.colaboradorId) : ""}
            scope={selected ? conversationScope(selected) : ""}
            currentUserId={session?.colaboradorId ?? ""}
            messages={messages}
            now={now}
            isOnline={snapshot.isOnline}
            lastSyncCompletedAt={snapshot.lastSyncCompletedAt}
            worksites={worksites}
            onBack={() => setMobilePane("thread")}
            onClose={() => setContextOpen(false)}
            onOpenAttachment={openAttachment}
          />
        </section>
        </div>

        {showCreate ? (
          <CreateConversationDialog
            obrasPromise={listObrasLocais()}
            alfa={isAlfa(session)}
            onClose={() => setShowCreate(false)}
            onCreated={async (conversation) => {
              await storeServerConversations([conversation]);
              await loadLocal();
              setSelectedId(conversation.id);
              setShowCreate(false);
            }}
          />
        ) : null}
      </main>
    </CortexShell>
  );
}
