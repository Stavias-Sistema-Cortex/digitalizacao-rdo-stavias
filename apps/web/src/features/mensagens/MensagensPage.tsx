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
  ConversaTipo,
  MensagemAnexoLocalRecord,
  MensagemSyncStatus,
  ObraLocalRecord,
} from "../../lib/db/db.types";
import { listObrasLocais } from "../../lib/db/obraLocalRepository";
import { syncNow } from "../../lib/sync/syncEngine";
import { getSession, hasOnlineSession, isAlfa } from "../auth/authSession";
import { CreateConversationDialog } from "./components/CreateConversationDialog";
import {
  IconCheckDouble,
  IconChevronLeft,
  IconClock,
  IconClose,
  IconFile,
  IconInfo,
  IconPaperclip,
  IconSend,
  IconSpinner,
  IconWarning,
} from "./components/icons";
import {
  formatClock,
  formatFileSize,
  messageFrom,
} from "./mensagensFormat";
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
import { mensagemStatusLabel } from "./mensagensQueue";
import {
  buildMessageTimeline,
  type ConversationPreview,
} from "./mensagensView";
import "./MensagensPage.css";

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
  const fileInput = useRef<HTMLInputElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

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

  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 140)}px`;
  }, [body, selectedId]);

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
      if (fileInput.current) fileInput.current.value = "";
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

  function openContext() {
    setContextOpen(true);
    setMobilePane("context");
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

        <section
          className={`mensagens-workspace mensagens-workspace--${mobilePane}${
            contextOpen ? " mensagens-workspace--drawer-open" : ""
          }`}
          aria-label="Mensagens"
        >
          <aside className="mensagens-conversations">
            <header className="mensagens-pane-heading">
              <strong>Conversas</strong>
              <span>{navigator.onLine ? "Online" : "Offline"}</span>
            </header>
            <form className="mensagens-search" onSubmit={handleSearch}>
              <label htmlFor="mensagens-search">Buscar no histórico</label>
              <div>
                <input
                  id="mensagens-search"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                  placeholder="Mensagem, medição, ocorrência…"
                />
                <button type="submit" aria-label="Buscar mensagens">
                  Buscar
                </button>
              </div>
            </form>

            {searchResults ? (
              <SearchResults
                results={searchResults}
                conversations={conversations}
                onChoose={chooseSearchResult}
                onClose={() => setSearchResults(null)}
              />
            ) : (
              <ConversationList
                loading={loading}
                conversations={conversations}
                selectedId={selectedId}
                currentUserId={session?.colaboradorId ?? ""}
                previews={previews}
                onSelect={chooseConversation}
              />
            )}
          </aside>

          <section className="mensagens-thread" aria-live="polite">
            {selected ? (
              <>
                <header className="mensagens-thread-header">
                  <button
                    type="button"
                    className="mensagens-mobile-back"
                    onClick={() => setMobilePane("list")}
                    aria-label="Voltar para conversas"
                  >
                    <IconChevronLeft />
                  </button>
                  <span className="mensagens-thread-avatar" aria-hidden="true">
                    {conversationName(selected, session?.colaboradorId)
                      .slice(0, 1)
                      .toUpperCase()}
                  </span>
                  <div className="mensagens-thread-heading">
                    <h2>{conversationName(selected, session?.colaboradorId)}</h2>
                    <p>
                      {conversationScope(selected)} ·{" "}
                      {selected.participantes.filter(activeParticipant).length}{" "}
                      participantes
                    </p>
                  </div>
                  <button
                    type="button"
                    className="mensagens-info-btn"
                    onClick={openContext}
                    aria-label="Ver contexto da conversa"
                  >
                    <IconInfo />
                  </button>
                </header>

                <ol className="mensagens-list">
                  {messages.length === 0 ? (
                    <li className="mensagens-empty">
                      Nenhuma mensagem nesta conversa.
                    </li>
                  ) : (
                    timeline.map((entry) => entry.kind === "date" ? (
                      <li className="mensagens-date-separator" key={entry.key}>
                        <span>{entry.label}</span>
                      </li>
                    ) : (
                      <MessageItem
                        key={entry.key}
                        message={entry.message}
                        showAuthor={entry.startsRun}
                        mine={entry.message.autorId === session?.colaboradorId}
                        isGroup={isGroup}
                        onOpenAttachment={openAttachment}
                        onRetry={handleRetry}
                      />
                    ))
                  )}
                </ol>

                <form className="mensagens-composer" onSubmit={handleSend}>
                  {files.length > 0 ? (
                    <ul className="mensagens-file-preview">
                      {files.map((file, index) => (
                        <li key={`${file.name}-${file.lastModified}`}>
                          <span>{file.name}</span>
                          <button
                            type="button"
                            onClick={() =>
                              setFiles((current) =>
                                current.filter((_, itemIndex) => itemIndex !== index),
                              )
                            }
                          >
                            Remover
                          </button>
                        </li>
                      ))}
                    </ul>
                  ) : null}
                  <div className="mensagens-composer-bar">
                    <label className="mensagens-attach" aria-label="Anexar arquivos">
                      <input
                        ref={fileInput}
                        type="file"
                        multiple
                        onChange={(event) => setFiles(Array.from(event.target.files ?? []))}
                      />
                      <IconPaperclip />
                    </label>
                    <textarea
                      ref={textareaRef}
                      id="mensagem-body"
                      value={body}
                      onChange={(event) =>
                        selectedId &&
                        setDrafts((current) => ({
                          ...current,
                          [selectedId]: event.target.value,
                        }))
                      }
                      onKeyDown={(event) => {
                        if (
                          event.key === "Enter" &&
                          !event.shiftKey &&
                          !event.nativeEvent.isComposing
                        ) {
                          event.preventDefault();
                          event.currentTarget.form?.requestSubmit();
                        }
                      }}
                      placeholder="Mensagem"
                      rows={1}
                    />
                    <button
                      type="submit"
                      className="mensagens-send"
                      disabled={sending || (!body.trim() && files.length === 0)}
                      aria-label="Enviar mensagem"
                    >
                      {sending ? <IconSpinner /> : <IconSend />}
                    </button>
                  </div>
                  {!navigator.onLine ? (
                    <span className="mensagens-composer-hint">
                      Offline — a mensagem será enviada quando reconectar.
                    </span>
                  ) : null}
                </form>
              </>
            ) : (
              <div className="mensagens-thread-empty">
                <h2>Selecione uma conversa</h2>
                <p>O histórico autorizado será carregado deste dispositivo.</p>
              </div>
            )}
          </section>

          {contextOpen ? (
            <button
              type="button"
              className="mensagens-drawer-backdrop"
              aria-label="Fechar contexto"
              onClick={() => setContextOpen(false)}
            />
          ) : null}
          <ConversationContext
            conversation={selected}
            messages={messages}
            worksites={worksites}
            onBack={() => setMobilePane("thread")}
            onClose={() => setContextOpen(false)}
            onOpenAttachment={openAttachment}
          />
        </section>

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

function MessageItem({
  message,
  showAuthor,
  mine,
  isGroup,
  onOpenAttachment,
  onRetry,
}: {
  message: MensagemComAnexos;
  showAuthor: boolean;
  mine: boolean;
  isGroup: boolean;
  onOpenAttachment: (attachment: MensagemAnexoLocalRecord) => Promise<void>;
  onRetry: (messageId: string) => Promise<void>;
}) {
  const failed = message.syncStatus === "FALHOU";
  const bubbleClass = [
    "mensagem-bubble",
    mine ? "mensagem-bubble--mine" : "mensagem-bubble--in",
    showAuthor ? "mensagem-bubble--tail" : "",
  ]
    .filter(Boolean)
    .join(" ");
  return (
    <li
      className={`mensagem-item${mine ? " mensagem-item--mine" : ""}${
        showAuthor ? " mensagem-item--lead" : ""
      }`}
    >
      <div className={bubbleClass}>
        {isGroup && !mine && showAuthor ? (
          <span className="mensagem-autor">{message.autorNome}</span>
        ) : null}
        {message.status === "EXCLUIDA" ? (
          <p className="mensagem-deleted">Mensagem excluída</p>
        ) : message.corpo ? (
          <p className="mensagem-corpo">{message.corpo}</p>
        ) : null}
        {message.anexos.length > 0 ? (
          <ul className="mensagem-attachments">
            {message.anexos.map((attachment) => (
              <li key={attachment.id}>
                <button
                  type="button"
                  onClick={() => void onOpenAttachment(attachment)}
                >
                  <IconFile />
                  <span>{attachment.nome}</span>
                  <small>{formatFileSize(attachment.tamanhoBytes)}</small>
                </button>
              </li>
            ))}
          </ul>
        ) : null}
        <span className="mensagem-meta">
          <time dateTime={message.criadaNoClienteEm}>
            {formatClock(message.criadaNoClienteEm)}
          </time>
          {mine ? <MessageTick status={message.syncStatus} /> : null}
        </span>
        {failed ? (
          <div className="mensagem-retry">
            <span>Falha ao enviar</span>
            <button type="button" onClick={() => void onRetry(message.id)}>
              Tentar novamente
            </button>
          </div>
        ) : null}
        {message.ultimoErro ? (
          <details className="mensagem-error">
            <summary>Detalhes da sincronização</summary>
            <p>{message.ultimoErro}</p>
          </details>
        ) : null}
      </div>
    </li>
  );
}

function MessageTick({ status }: { status: MensagemSyncStatus }) {
  const label = mensagemStatusLabel(status);
  if (status === "FALHOU") {
    return (
      <IconWarning className="mensagem-tick mensagem-tick--fail" title={label} />
    );
  }
  if (status === "SINCRONIZADO") {
    return <IconCheckDouble className="mensagem-tick" title={label} />;
  }
  return <IconClock className="mensagem-tick" title={label} />;
}

function ConversationContext({
  conversation,
  messages,
  worksites,
  onBack,
  onClose,
  onOpenAttachment,
}: {
  conversation: ConversaLocalRecord | null;
  messages: MensagemComAnexos[];
  worksites: ObraLocalRecord[];
  onBack: () => void;
  onClose: () => void;
  onOpenAttachment: (attachment: MensagemAnexoLocalRecord) => Promise<void>;
}) {
  if (!conversation) {
    return (
      <aside className="mensagens-context">
        <p className="mensagens-list-status">O contexto aparece ao abrir uma conversa.</p>
      </aside>
    );
  }
  const worksite = worksites.find((item) => item.id === conversation.obraId);
  const participants = conversation.participantes.filter(activeParticipant);
  const attachments = messages.flatMap((message) => message.anexos);
  return (
    <aside className="mensagens-context" aria-label="Contexto da conversa">
      <header>
        <button
          type="button"
          className="mensagens-mobile-back"
          onClick={onBack}
          aria-label="Voltar para a conversa"
        >
          <IconChevronLeft />
        </button>
        <div>
          <strong>Contexto</strong>
          <span>{conversationScope(conversation)}</span>
        </div>
        <button
          type="button"
          className="mensagens-drawer-close"
          onClick={onClose}
          aria-label="Fechar contexto"
        >
          <IconClose />
        </button>
      </header>

      {conversation.obraId ? (
        <section>
          <h3>Obra</h3>
          <strong>{worksite?.nome ?? "Obra vinculada"}</strong>
          <p>{worksite?.codigoContrato ?? shortIdentifier(conversation.obraId)}</p>
        </section>
      ) : null}

      <section>
        <h3>Pessoas</h3>
        <ul className="mensagens-context-people">
          {participants.map((participant) => (
            <li key={participant.colaboradorId}>
              <span aria-hidden="true">{participant.nome.slice(0, 1).toUpperCase()}</span>
              <div>
                <strong>{participant.nome}</strong>
                <small>{participant.papel === "ADMIN" ? "Administrador" : "Membro"}</small>
              </div>
            </li>
          ))}
        </ul>
      </section>

      <section>
        <h3>Documentos</h3>
        {attachments.length === 0 ? (
          <p>Nenhum documento nesta conversa.</p>
        ) : (
          <ul className="mensagens-context-documents">
            {attachments.map((attachment) => (
              <li key={attachment.id}>
                <button type="button" onClick={() => void onOpenAttachment(attachment)}>
                  <strong>{attachment.nome}</strong>
                  <small>{formatFileSize(attachment.tamanhoBytes)}</small>
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </aside>
  );
}

function ConversationList(props: {
  loading: boolean;
  conversations: ConversaLocalRecord[];
  selectedId: string | null;
  currentUserId: string;
  previews: Record<string, ConversationPreview>;
  onSelect: (id: string) => void;
}) {
  if (props.loading) return <p className="mensagens-list-status">Carregando conversas…</p>;
  if (props.conversations.length === 0) {
    return (
      <p className="mensagens-list-status">
        Nenhuma conversa autorizada foi encontrada.
      </p>
    );
  }
  return (
    <ul className="mensagens-conversation-list">
      {props.conversations.map((conversation) => (
        <li key={conversation.id}>
          <button
            type="button"
            className={conversation.id === props.selectedId ? "active" : ""}
            onClick={() => props.onSelect(conversation.id)}
          >
            <span className="mensagens-avatar" aria-hidden="true">
              {conversationName(conversation, props.currentUserId).slice(0, 1).toUpperCase()}
            </span>
            <span>
              <strong>{conversationName(conversation, props.currentUserId)}</strong>
              <small>
                {props.previews[conversation.id]?.text ?? conversationScope(conversation)}
              </small>
            </span>
            <time>{formatListDate(
              props.previews[conversation.id]?.at ?? conversation.atualizadaEm,
            )}</time>
          </button>
        </li>
      ))}
    </ul>
  );
}

function SearchResults(props: {
  results: MensagemComAnexos[];
  conversations: ConversaLocalRecord[];
  onChoose: (message: MensagemComAnexos) => void;
  onClose: () => void;
}) {
  return (
    <div className="mensagens-search-results">
      <header>
        <strong>{props.results.length} resultado(s)</strong>
        <button type="button" onClick={props.onClose}>Voltar</button>
      </header>
      <ul>
        {props.results.map((message) => (
          <li key={message.id}>
            <button type="button" onClick={() => props.onChoose(message)}>
              <strong>{message.autorNome}</strong>
              <span>{message.corpo || "Mensagem com anexo"}</span>
              <small>
                {props.conversations.find((item) => item.id === message.conversaId)?.titulo ||
                  formatMessageTime(message.criadaNoClienteEm)}
              </small>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}


function conversationName(conversation: ConversaLocalRecord, currentUserId?: string) {
  if (conversation.titulo) return conversation.titulo;
  const others = conversation.participantes
    .filter(activeParticipant)
    .filter((participant) => participant.colaboradorId !== currentUserId)
    .map((participant) => participant.nome);
  return others.join(", ") || "Conversa direta";
}

function conversationScope(conversation: ConversaLocalRecord) {
  const labels: Record<ConversaTipo, string> = {
    DIRETA: "Conversa direta",
    GRUPO: "Grupo",
    EQUIPE: "Equipe da obra",
    OBRA: "Conversa da obra",
  };
  return labels[conversation.tipo];
}

function activeParticipant(participant: ConversaLocalRecord["participantes"][number]) {
  return participant.status === "ATIVO";
}

function formatMessageTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat("pt-BR", {
        dateStyle: "short",
        timeStyle: "short",
      }).format(date);
}

function formatListDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? ""
    : new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "2-digit" }).format(date);
}

function shortIdentifier(value: string): string {
  return value.length > 12 ? `${value.slice(0, 8)}…` : value;
}

