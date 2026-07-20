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
  const fileInput = useRef<HTMLInputElement>(null);

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
    setMobilePane("thread");
  }

  function chooseConversation(id: string) {
    setSelectedId(id);
    setSearchParams({ conversa: id });
    setMobilePane("thread");
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
          className={`mensagens-workspace mensagens-workspace--${mobilePane}`}
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
                  >
                    Conversas
                  </button>
                  <div>
                    <h2>{conversationName(selected, session?.colaboradorId)}</h2>
                    <p>{conversationScope(selected)}</p>
                  </div>
                  <button
                    type="button"
                    className="mensagens-context-toggle"
                    onClick={() => setMobilePane("context")}
                  >
                    {selected.participantes.filter(activeParticipant).length} participantes
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
                  <label htmlFor="mensagem-body">Nova mensagem</label>
                  <textarea
                    id="mensagem-body"
                    value={body}
                    onChange={(event) => selectedId && setDrafts((current) => ({
                      ...current,
                      [selectedId]: event.target.value,
                    }))}
                    onKeyDown={(event) => {
                      if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
                        event.preventDefault();
                        event.currentTarget.form?.requestSubmit();
                      }
                    }}
                    placeholder="Mensagem"
                    rows={1}
                  />
                  <div>
                    <label className="mensagens-attach">
                      <input
                        ref={fileInput}
                        type="file"
                        multiple
                        onChange={(event) => setFiles(Array.from(event.target.files ?? []))}
                      />
                      Anexar arquivos
                    </label>
                    <span>
                      {navigator.onLine ? "Conectado" : "Offline — será enviado depois"}
                    </span>
                    <button
                      type="submit"
                      className="mensagens-primary"
                      disabled={sending || (!body.trim() && files.length === 0)}
                    >
                      {sending ? "Salvando…" : "Enviar"}
                    </button>
                  </div>
                </form>
              </>
            ) : (
              <div className="mensagens-thread-empty">
                <h2>Selecione uma conversa</h2>
                <p>O histórico autorizado será carregado deste dispositivo.</p>
              </div>
            )}
          </section>

          <ConversationContext
            conversation={selected}
            messages={messages}
            worksites={worksites}
            onBack={() => setMobilePane("thread")}
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
  onOpenAttachment,
  onRetry,
}: {
  message: MensagemComAnexos;
  showAuthor: boolean;
  mine: boolean;
  onOpenAttachment: (attachment: MensagemAnexoLocalRecord) => Promise<void>;
  onRetry: (messageId: string) => Promise<void>;
}) {
  const showSync = message.syncStatus !== "SINCRONIZADO";
  return (
    <li className={mine ? "mensagem-item mensagem-item--mine" : "mensagem-item"}>
      <article>
        <header className={showAuthor ? "" : "is-continuation"}>
          {showAuthor ? <strong>{message.autorNome}</strong> : <span />}
          <time dateTime={message.criadaNoClienteEm}>
            {formatMessageTimeOnly(message.criadaNoClienteEm)}
          </time>
        </header>
        {message.status === "EXCLUIDA" ? (
          <p className="mensagem-deleted">Mensagem excluída</p>
        ) : message.corpo ? (
          <p>{message.corpo}</p>
        ) : null}
        {message.anexos.length > 0 ? (
          <ul className="mensagem-attachments">
            {message.anexos.map((attachment) => (
              <li key={attachment.id}>
                <button
                  type="button"
                  onClick={() => void onOpenAttachment(attachment)}
                >
                  <b aria-hidden="true">DOC</b>
                  <span>{attachment.nome}</span>
                  <small>{formatFileSize(attachment.tamanhoBytes)}</small>
                </button>
              </li>
            ))}
          </ul>
        ) : null}
        {showSync ? (
          <footer>
            <span className={`mensagem-sync mensagem-sync--${message.syncStatus.toLowerCase()}`}>
              {mensagemStatusLabel(message.syncStatus)}
            </span>
            {message.syncStatus === "FALHOU" ? (
              <button type="button" onClick={() => void onRetry(message.id)}>
                Tentar novamente
              </button>
            ) : null}
          </footer>
        ) : null}
        {message.ultimoErro ? (
          <details>
            <summary>Detalhes da sincronização</summary>
            <p>{message.ultimoErro}</p>
          </details>
        ) : null}
      </article>
    </li>
  );
}

function ConversationContext({
  conversation,
  messages,
  worksites,
  onBack,
  onOpenAttachment,
}: {
  conversation: ConversaLocalRecord | null;
  messages: MensagemComAnexos[];
  worksites: ObraLocalRecord[];
  onBack: () => void;
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
        <button type="button" className="mensagens-mobile-back" onClick={onBack}>
          Conversa
        </button>
        <div><strong>Contexto</strong><span>{conversationScope(conversation)}</span></div>
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
