import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
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
import {
  buscarColaboradores,
  buscarColaboradoresDaObra,
  type ColaboradorDaObra,
  type ColaboradorLookup,
} from "../rdos/rdoLookupApi";
import {
  createConversationApi,
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

type DirectoryPerson = {
  id: string;
  nome: string;
  detalhe: string;
};

const CREATE_TYPES: { value: ConversaTipo; label: string }[] = [
  { value: "DIRETA", label: "Direta" },
  { value: "GRUPO", label: "Grupo" },
  { value: "OBRA", label: "Obra" },
];

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
                        showAuthor={entry.showAuthor}
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
            {formatMessageTimeOnly(message.criadaNoClienteEm)}
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

function CreateConversationDialog(props: {
  obrasPromise: Promise<ObraLocalRecord[]>;
  alfa: boolean;
  onClose: () => void;
  onCreated: (conversation: Awaited<ReturnType<typeof createConversationApi>>) => Promise<void>;
}) {
  const [type, setType] = useState<ConversaTipo>("DIRETA");
  const [title, setTitle] = useState("");
  const [obras, setObras] = useState<ObraLocalRecord[]>([]);
  const [obraId, setObraId] = useState("");
  const [query, setQuery] = useState("");
  const [people, setPeople] = useState<DirectoryPerson[]>([]);
  const [selectedPeople, setSelectedPeople] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    void props.obrasPromise.then(setObras).catch((cause) => setError(messageFrom(cause)));
  }, [props.obrasPromise]);

  async function searchPeople() {
    setBusy(true);
    setError("");
    try {
      if (!props.alfa && !obraId) {
        throw new Error("Selecione uma obra para consultar participantes autorizados.");
      }
      const found = obraId
        ? mapWorksitePeople(await buscarColaboradoresDaObra(obraId))
        : mapGlobalPeople(await buscarColaboradores(query));
      setPeople(
        found.filter((person) =>
          person.nome.toLocaleLowerCase("pt-BR").includes(query.trim().toLocaleLowerCase("pt-BR")),
        ),
      );
    } catch (cause: unknown) {
      setError(messageFrom(cause));
    } finally {
      setBusy(false);
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      if (type === "DIRETA" && selectedPeople.length !== 1) {
        throw new Error("Selecione uma pessoa para a conversa direta.");
      }
      if (type === "GRUPO" && (!title.trim() || selectedPeople.length < 1)) {
        throw new Error("Informe o nome do grupo e selecione ao menos uma pessoa.");
      }
      if (type === "OBRA" && !obraId) {
        throw new Error("Selecione a obra da conversa.");
      }
      const created = await createConversationApi({
        tipo: type,
        titulo: type === "DIRETA" ? null : title,
        obraId: type === "OBRA" ? obraId : null,
        participanteIds: selectedPeople,
      });
      await props.onCreated(created);
    } catch (cause: unknown) {
      setError(messageFrom(cause));
      setBusy(false);
    }
  }

  return (
    <div className="mensagens-dialog-backdrop" role="presentation">
      <section
        className="mensagens-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="nova-conversa-title"
      >
        <header>
          <div>
            <p className="eyebrow">Participantes autorizados</p>
            <h2 id="nova-conversa-title">Nova conversa</h2>
          </div>
          <button type="button" onClick={props.onClose} aria-label="Fechar">×</button>
        </header>
        <form onSubmit={submit}>
          <label>
            Tipo
            <select
              value={type}
              onChange={(event: ChangeEvent<HTMLSelectElement>) => {
                setType(event.target.value as ConversaTipo);
                setSelectedPeople([]);
              }}
            >
              {CREATE_TYPES.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </label>
          {type !== "DIRETA" ? (
            <label>
              Nome da conversa
              <input value={title} onChange={(event) => setTitle(event.target.value)} />
            </label>
          ) : null}
          <label>
            {type === "OBRA" ? "Obra" : "Obra usada para consultar pessoas"}
            <select value={obraId} onChange={(event) => setObraId(event.target.value)}>
              <option value="">{props.alfa ? "Catálogo global" : "Selecione"}</option>
              {obras.map((obra) => (
                <option key={obra.id} value={obra.id}>{obra.nome}</option>
              ))}
            </select>
          </label>
          <div className="mensagens-directory-search">
            <label>
              Buscar participante
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Nome"
              />
            </label>
            <button type="button" onClick={() => void searchPeople()} disabled={busy}>
              Consultar
            </button>
          </div>
          <fieldset>
            <legend>Participantes</legend>
            {people.length === 0 ? (
              <p>Consulte o diretório autorizado para selecionar pessoas.</p>
            ) : (
              people.map((person) => (
                <label key={person.id} className="mensagens-person">
                  <input
                    type={type === "DIRETA" ? "radio" : "checkbox"}
                    name="participantes"
                    checked={selectedPeople.includes(person.id)}
                    onChange={() =>
                      setSelectedPeople((current) =>
                        type === "DIRETA"
                          ? [person.id]
                          : current.includes(person.id)
                            ? current.filter((id) => id !== person.id)
                            : [...current, person.id],
                      )
                    }
                  />
                  <span><strong>{person.nome}</strong><small>{person.detalhe}</small></span>
                </label>
              ))
            )}
          </fieldset>
          {error ? <p className="mensagens-form-error" role="alert">{error}</p> : null}
          <footer>
            <button type="button" onClick={props.onClose}>Cancelar</button>
            <button type="submit" className="mensagens-primary" disabled={busy}>
              {busy ? "Criando…" : "Criar conversa"}
            </button>
          </footer>
        </form>
      </section>
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

function formatMessageTimeOnly(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat("pt-BR", {
        hour: "2-digit",
        minute: "2-digit",
      }).format(date);
}

function formatListDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? ""
    : new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "2-digit" }).format(date);
}

function formatFileSize(bytes: number) {
  return bytes < 1024 * 1024
    ? `${Math.max(1, Math.round(bytes / 1024))} KB`
    : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function shortIdentifier(value: string): string {
  return value.length > 12 ? `${value.slice(0, 8)}…` : value;
}

function mapWorksitePeople(values: ColaboradorDaObra[]): DirectoryPerson[] {
  return values.map((person) => ({
    id: person.id,
    nome: person.nome || "Colaborador",
    detalhe: person.nomePerfil || person.nomeGrupo || "Vínculo ativo",
  }));
}

function mapGlobalPeople(values: ColaboradorLookup[]): DirectoryPerson[] {
  return values
    .filter((person) => person.ativo)
    .map((person) => ({
      id: person.id,
      nome: person.nome || "Colaborador",
      detalhe: person.nomePerfil || person.nomeGrupo || "Ativo",
    }));
}

function messageFrom(cause: unknown): string {
  return cause instanceof Error ? cause.message : "Não foi possível concluir a operação.";
}

type IconProps = { className?: string; title?: string };

function IconPaperclip() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M21.44 11.05 12.25 20.24a5.5 5.5 0 0 1-7.78-7.78l8.49-8.49a3.67 3.67 0 0 1 5.19 5.19l-8.49 8.49a1.83 1.83 0 0 1-2.6-2.6l7.78-7.78" />
    </svg>
  );
}

function IconSend() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M3.4 3.1a1 1 0 0 0-1.36 1.22l2.1 6.06a1 1 0 0 0 .82.66l7.72 1.02a.34.34 0 0 1 0 .68l-7.72 1.02a1 1 0 0 0-.82.66l-2.1 6.06A1 1 0 0 0 3.4 21.9l17.9-8.98a1 1 0 0 0 0-1.84Z" />
    </svg>
  );
}

function IconSpinner() {
  return (
    <svg
      className="mensagens-spin"
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.4"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <path d="M12 3a9 9 0 1 0 9 9" />
    </svg>
  );
}

function IconInfo() {
  return (
    <svg
      width="22"
      height="22"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="9" />
      <line x1="12" y1="11" x2="12" y2="16.5" strokeLinecap="round" />
      <circle cx="12" cy="7.6" r="0.9" fill="currentColor" stroke="none" />
    </svg>
  );
}

function IconChevronLeft() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <polyline points="15 18 9 12 15 6" />
    </svg>
  );
}

function IconClose() {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <line x1="6" y1="6" x2="18" y2="18" />
      <line x1="18" y1="6" x2="6" y2="18" />
    </svg>
  );
}

function IconFile() {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
      <polyline points="14 3 14 8 19 8" />
    </svg>
  );
}

function IconClock({ className, title }: IconProps) {
  return (
    <svg
      className={className}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      role={title ? "img" : undefined}
      aria-hidden={title ? undefined : true}
    >
      {title ? <title>{title}</title> : null}
      <circle cx="12" cy="12" r="9" />
      <polyline points="12 7.5 12 12 15 14" />
    </svg>
  );
}

function IconCheckDouble({ className, title }: IconProps) {
  return (
    <svg
      className={className}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      role={title ? "img" : undefined}
      aria-hidden={title ? undefined : true}
    >
      {title ? <title>{title}</title> : null}
      <path d="M2 12.5 6 16.5 13 8" />
      <path d="M8 13 11.5 16.5 18.5 8" />
    </svg>
  );
}

function IconWarning({ className, title }: IconProps) {
  return (
    <svg
      className={className}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      role={title ? "img" : undefined}
      aria-hidden={title ? undefined : true}
    >
      {title ? <title>{title}</title> : null}
      <path d="M12 3.5 2.3 20.5h19.4z" />
      <line x1="12" y1="10" x2="12" y2="14.5" />
      <circle cx="12" cy="17.4" r="0.5" fill="currentColor" stroke="none" />
    </svg>
  );
}
