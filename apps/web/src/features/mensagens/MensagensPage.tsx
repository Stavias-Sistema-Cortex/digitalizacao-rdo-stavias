import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type FormEvent,
  type KeyboardEvent,
} from "react";
import { useSearchParams } from "react-router-dom";

import { CortexShell } from "../../components/shell/CortexShell";
import {
  getSession,
  isAlfa,
} from "../auth/authSession";
import {
  getLocalConversation,
  listLocalConversationParticipants,
  listLocalConversations,
  markLocalConversationRead,
} from "../../lib/db/conversationLocalRepository";
import type {
  ColaboradorLocalRecord,
  LocalConversationParticipantRecord,
  LocalConversationRecord,
  LocalMessageAttachmentRecord,
  LocalMessageRecord,
  LocalMessageReferenceRecord,
  ObraLocalRecord,
} from "../../lib/db/db.types";
import {
  listLocalMessageAttachments,
  listLocalMessageReferences,
  listLocalMessages,
} from "../../lib/db/messageLocalRepository";
import { listObrasLocais } from "../../lib/db/obraLocalRepository";
import { syncNow } from "../../lib/sync/syncEngine";
import {
  hidratarColaboradoresAcademy,
  listarColaboradoresConhecidos,
} from "../tarefas/colaboradoresAcademy";
import { hydrateObrasRelacionadas } from "../home/homeHydration";
import {
  createConversation,
  downloadMessageAttachment,
  fetchConversation,
  fetchConversations,
  fetchMessages,
  markMessageRead,
  type MessagePageDto,
} from "./messageApi";
import {
  hydrateConversation,
  hydrateConversationPage,
  hydrateMessagePage,
} from "./messageHydration";
import {
  retryLocalMessage,
  saveMessageOffline,
} from "./messageLocalService";
import {
  conversationDisplayName,
  conversationStaviaTarget,
  groupMessagesByDay,
  messageDeliveryLabel,
  participantInitials,
} from "./messageViewModel";
import { useStaviaLauncher } from "../stavia/useStaviaLauncher";
import "./MensagensPage.css";

const ACCEPTED_ATTACHMENT_TYPES = [
  "image/jpeg",
  "image/png",
  "image/webp",
  "application/pdf",
  "text/plain",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
];
const MAX_FILE_BYTES = 10 * 1024 * 1024;
const MAX_MESSAGE_FILE_BYTES = 25 * 1024 * 1024;

interface ThreadItem {
  message: LocalMessageRecord;
  references: LocalMessageReferenceRecord[];
  attachments: LocalMessageAttachmentRecord[];
}

interface NewConversationForm {
  tipo: "OBRA" | "DIRETA" | "GRUPO";
  titulo: string;
  obraId: string;
  participanteIds: string[];
}

const EMPTY_CONVERSATION_FORM: NewConversationForm = {
  tipo: "OBRA",
  titulo: "",
  obraId: "",
  participanteIds: [],
};

function formatTime(value: string | null): string {
  if (!value) {
    return "";
  }
  return new Intl.DateTimeFormat("pt-BR", {
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

function formatConversationTime(value: string): string {
  const date = new Date(value);
  const today = new Date();
  if (date.toDateString() === today.toDateString()) {
    return formatTime(value);
  }
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "2-digit",
  }).format(date);
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${Math.round(bytes / 1024)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function filterLocalConversations(
  conversations: LocalConversationRecord[],
  search: string,
): LocalConversationRecord[] {
  const normalized = search.trim().toLocaleLowerCase("pt-BR");
  if (!normalized) {
    return conversations;
  }
  return conversations.filter((conversation) =>
    [
      conversationDisplayName(conversation),
      conversation.obraNome,
      conversation.equipeNome,
      conversation.ultimaMensagemPrevia,
    ].some((value) => value?.toLocaleLowerCase("pt-BR").includes(normalized)),
  );
}

async function readLocalThread(conversationId: string): Promise<ThreadItem[]> {
  const messages = await listLocalMessages(conversationId);
  return Promise.all(
    messages.map(async (message) => ({
      message,
      references: await listLocalMessageReferences(message.id),
      attachments: await listLocalMessageAttachments(message.id),
    })),
  );
}

function Icon({ name }: { name: "search" | "plus" | "back" | "file" | "send" | "refresh" }) {
  const paths = {
    search: <><circle cx="11" cy="11" r="6.5" /><path d="m16 16 4 4" /></>,
    plus: <><path d="M12 5v14M5 12h14" /></>,
    back: <path d="m15 18-6-6 6-6" />,
    file: <><path d="M8 12.5 13.4 7a3 3 0 0 1 4.2 4.3l-7 7a5 5 0 0 1-7.1-7.1l7-7" /><path d="m7 14 6.5-6.5" /></>,
    send: <><path d="m4 4 17 8-17 8 3-8-3-8Z" /><path d="M7 12h14" /></>,
    refresh: <><path d="M20 7v5h-5" /><path d="M4 17v-5h5" /><path d="M6.1 8a7 7 0 0 1 11.5-2L20 8M4 16l2.4 2A7 7 0 0 0 18 16" /></>,
  };
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {paths[name]}
    </svg>
  );
}

export function MensagensPage() {
  const session = getSession();
  const { setStaviaContext } = useStaviaLauncher();
  const [searchParams, setSearchParams] = useSearchParams();
  const selectedConversationId = searchParams.get("conversa");
  const [search, setSearch] = useState("");
  const [conversations, setConversations] = useState<LocalConversationRecord[]>([]);
  const [conversation, setConversation] = useState<LocalConversationRecord | null>(null);
  const [participants, setParticipants] = useState<LocalConversationParticipantRecord[]>([]);
  const [thread, setThread] = useState<ThreadItem[]>([]);
  const [isListLoading, setIsListLoading] = useState(true);
  const [isThreadLoading, setIsThreadLoading] = useState(false);
  const [listError, setListError] = useState<string | null>(null);
  const [threadError, setThreadError] = useState<string | null>(null);
  const [cursor, setCursor] = useState<MessagePageDto["proximoCursor"]>(null);
  const [hasMore, setHasMore] = useState(false);
  const [draft, setDraft] = useState("");
  const [files, setFiles] = useState<File[]>([]);
  const [sendError, setSendError] = useState<string | null>(null);
  const [isSending, setIsSending] = useState(false);
  const [reloadTick, setReloadTick] = useState(0);
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [newConversation, setNewConversation] = useState<NewConversationForm>(EMPTY_CONVERSATION_FORM);
  const [obras, setObras] = useState<ObraLocalRecord[]>([]);
  const [collaborators, setCollaborators] = useState<ColaboradorLocalRecord[]>([]);
  const [createError, setCreateError] = useState<string | null>(null);
  const [isCreating, setIsCreating] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const messageListRef = useRef<HTMLDivElement>(null);

  const reload = useCallback(() => setReloadTick((value) => value + 1), []);

  useEffect(() => {
    setStaviaContext(conversationStaviaTarget(conversation));
  }, [conversation, setStaviaContext]);

  useEffect(() => {
    let cancelled = false;
    const timeoutId = window.setTimeout(() => {
      async function loadConversations() {
        setIsListLoading(true);
        setListError(null);
        const local = filterLocalConversations(await listLocalConversations(), search);
        if (!cancelled) {
          setConversations(local);
          setIsListLoading(false);
        }

        if (!navigator.onLine || !session?.token) {
          return;
        }
        try {
          const page = await fetchConversations({ texto: search, page: 0, size: 100 });
          await hydrateConversationPage(page);
          const refreshed = filterLocalConversations(await listLocalConversations(), search);
          if (!cancelled) {
            setConversations(refreshed);
          }
        } catch (error: unknown) {
          if (!cancelled && local.length === 0) {
            setListError(error instanceof Error ? error.message : "Não foi possível carregar as conversas.");
          }
        } finally {
          if (!cancelled) {
            setIsListLoading(false);
          }
        }
      }
      void loadConversations();
    }, search ? 250 : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timeoutId);
    };
  }, [reloadTick, search, session?.token]);

  useEffect(() => {
    if (!selectedConversationId) {
      return;
    }
    const conversationId = selectedConversationId;
    let cancelled = false;
    async function applyLocalProjection() {
      const [localConversation, localParticipants, localThread] = await Promise.all([
        getLocalConversation(conversationId),
        listLocalConversationParticipants(conversationId),
        readLocalThread(conversationId),
      ]);
      if (!cancelled) {
        setConversation(localConversation ?? null);
        setParticipants(localParticipants);
        setThread(localThread);
      }
    }
    async function loadThread() {
      setIsThreadLoading(true);
      setThreadError(null);
      await applyLocalProjection();
      if (!navigator.onLine || !session?.token) {
        if (!cancelled) setIsThreadLoading(false);
        return;
      }
      try {
        const [detail, page] = await Promise.all([
          fetchConversation(conversationId),
          fetchMessages(conversationId, { limit: 50 }),
        ]);
        await hydrateConversation(detail);
        await hydrateMessagePage(page);
        const lastMessage = page.items.at(-1);
        if (lastMessage && lastMessage.remetenteId !== session.colaboradorId) {
          try {
            await markMessageRead(lastMessage.id);
            await markLocalConversationRead(conversationId);
          } catch {
            // A leitura não impede a consulta offline do histórico já hidratado.
          }
        }
        if (!cancelled) {
          setCursor(page.proximoCursor);
          setHasMore(page.hasMore);
        }
        await applyLocalProjection();
      } catch (error: unknown) {
        if (!cancelled && (await listLocalMessages(conversationId)).length === 0) {
          setThreadError(error instanceof Error ? error.message : "Não foi possível carregar as mensagens.");
        }
      } finally {
        if (!cancelled) setIsThreadLoading(false);
      }
    }
    void loadThread();
    return () => {
      cancelled = true;
    };
  }, [reloadTick, selectedConversationId, session?.colaboradorId, session?.token]);

  useEffect(() => {
    const interval = window.setInterval(() => {
      if (document.visibilityState === "visible") reload();
    }, 5_000);
    return () => window.clearInterval(interval);
  }, [reload]);

  useEffect(() => {
    const list = messageListRef.current;
    if (!list || thread.length === 0) return;
    const frame = window.requestAnimationFrame(() => {
      list.scrollTop = list.scrollHeight;
    });
    return () => window.cancelAnimationFrame(frame);
  }, [selectedConversationId, thread.length]);

  const groupedMessages = useMemo(
    () => groupMessagesByDay(thread.map((item) => item.message)),
    [thread],
  );
  const threadById = useMemo(
    () => new Map(thread.map((item) => [item.message.id, item])),
    [thread],
  );

  function selectConversation(id: string) {
    setSearchParams({ conversa: id });
  }

  function validateFiles(selected: File[]): string | null {
    const invalidType = selected.find((file) => !ACCEPTED_ATTACHMENT_TYPES.includes(file.type));
    if (invalidType) return `O formato de ${invalidType.name} não é aceito.`;
    const oversized = selected.find((file) => file.size > MAX_FILE_BYTES);
    if (oversized) return `${oversized.name} ultrapassa o limite de 10 MB.`;
    const total = [...files, ...selected].reduce((sum, file) => sum + file.size, 0);
    return total > MAX_MESSAGE_FILE_BYTES ? "Os anexos desta mensagem ultrapassam 25 MB." : null;
  }

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const selected = [...(event.target.files ?? [])];
    const error = validateFiles(selected);
    if (error) {
      setSendError(error);
    } else {
      setFiles((current) => [...current, ...selected]);
      setSendError(null);
    }
    event.target.value = "";
  }

  async function sendMessage(event?: FormEvent) {
    event?.preventDefault();
    if (!conversation || (!draft.trim() && files.length === 0) || isSending) return;
    setIsSending(true);
    setSendError(null);
    try {
      const references: Array<{ tipoObjeto: string; objetoId: string }> = [];
      if (conversation.obraId) references.push({ tipoObjeto: "OBRA", objetoId: conversation.obraId });
      if (conversation.equipeId) references.push({ tipoObjeto: "EQUIPE", objetoId: conversation.equipeId });
      await saveMessageOffline({
        conversaId: conversation.id,
        remetenteId: session?.colaboradorId ?? null,
        remetenteNome: session?.nome ?? null,
        texto: draft,
        referencias: references,
        anexos: files.map((file) => ({ nomeOriginal: file.name, mimeType: file.type, arquivo: file })),
      });
      setDraft("");
      setFiles([]);
      setThread(await readLocalThread(conversation.id));
      reload();
      if (navigator.onLine && session?.token) {
        try {
          await syncNow();
        } finally {
          setThread(await readLocalThread(conversation.id));
          reload();
        }
      }
    } catch (error: unknown) {
      setSendError(error instanceof Error ? error.message : "Não foi possível preparar a mensagem.");
    } finally {
      setIsSending(false);
    }
  }

  function handleComposerKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      void sendMessage();
    }
  }

  async function retryMessage(messageId: string) {
    setSendError(null);
    try {
      await retryLocalMessage(messageId);
      setThread(await readLocalThread(conversation?.id ?? ""));
      if (navigator.onLine && session?.token) await syncNow();
      reload();
    } catch (error: unknown) {
      setSendError(error instanceof Error ? error.message : "Não foi possível tentar novamente.");
    }
  }

  async function downloadAttachment(attachment: LocalMessageAttachmentRecord) {
    setThreadError(null);
    try {
      const content = attachment.arquivo ?? await downloadMessageAttachment(attachment.mensagemId, attachment.id);
      const url = URL.createObjectURL(content);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = attachment.nomeSeguro || attachment.nomeOriginal;
      anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (error: unknown) {
      setThreadError(error instanceof Error ? error.message : "Não foi possível baixar o anexo.");
    }
  }

  async function loadOlderMessages() {
    if (!conversation || !cursor) return;
    setIsThreadLoading(true);
    try {
      const page = await fetchMessages(conversation.id, {
        limit: 50,
        antesDeClienteEm: cursor.enviadaClienteEm,
        antesDeServidorEm: cursor.criadaServidorEm,
        antesDeId: cursor.id,
      });
      await hydrateMessagePage(page);
      setCursor(page.proximoCursor);
      setHasMore(page.hasMore);
      setThread(await readLocalThread(conversation.id));
    } catch (error: unknown) {
      setThreadError(error instanceof Error ? error.message : "Não foi possível carregar mensagens anteriores.");
    } finally {
      setIsThreadLoading(false);
    }
  }

  async function openCreateConversation() {
    setIsCreateOpen(true);
    setCreateError(null);
    try {
      if (navigator.onLine && session?.token) await hydrateObrasRelacionadas();
    } catch {
      // O formulário continua com as obras já conhecidas localmente.
    }
    setObras(await listObrasLocais());
    setCollaborators(await listarColaboradoresConhecidos());
  }

  async function loadCollaboratorsForWorksite(obraId: string) {
    setNewConversation((current) => ({ ...current, obraId, participanteIds: [] }));
    if (!obraId) return;
    try {
      if (navigator.onLine && session?.token) await hidratarColaboradoresAcademy("", obraId);
    } catch {
      // Mantém o catálogo local conhecido.
    }
    setCollaborators((await listarColaboradoresConhecidos()).filter(
      (collaborator) => collaborator.id !== session?.colaboradorId,
    ));
  }

  async function submitNewConversation(event: FormEvent) {
    event.preventDefault();
    if (!navigator.onLine || !session?.token) {
      setCreateError("Conecte-se para criar a conversa. Mensagens existentes continuam disponíveis offline.");
      return;
    }
    if (!newConversation.obraId && !isAlfa(session)) {
      setCreateError("Escolha uma obra compartilhada para esta conversa.");
      return;
    }
    if (newConversation.tipo === "DIRETA" && newConversation.participanteIds.length !== 1) {
      setCreateError("Escolha uma pessoa para a conversa direta.");
      return;
    }
    setIsCreating(true);
    setCreateError(null);
    try {
      const created = await createConversation({
        id: crypto.randomUUID(),
        tipo: newConversation.tipo,
        titulo: newConversation.titulo.trim() || null,
        obraId: newConversation.obraId || null,
        equipeId: null,
        participanteIds: newConversation.participanteIds,
      });
      await hydrateConversation(created);
      setIsCreateOpen(false);
      setNewConversation(EMPTY_CONVERSATION_FORM);
      setSearchParams({ conversa: created.id });
      reload();
    } catch (error: unknown) {
      setCreateError(error instanceof Error ? error.message : "Não foi possível criar a conversa.");
    } finally {
      setIsCreating(false);
    }
  }

  return (
    <CortexShell active="mensagens" onRefresh={reload} isRefreshing={isListLoading || isThreadLoading}>
      <main className={`messages-page ${selectedConversationId ? "messages-page--thread-open" : ""}`}>
        <aside className="messages-inbox" aria-label="Conversas">
          <header className="messages-inbox-header">
            <div>
              <p className="messages-kicker">Comunicação operacional</p>
              <h1>Mensagens</h1>
            </div>
            <button type="button" className="messages-icon-button messages-new-button" onClick={() => void openCreateConversation()} aria-label="Criar conversa" title="Criar conversa">
              <Icon name="plus" />
            </button>
          </header>
          <label className="messages-search">
            <Icon name="search" />
            <span className="sr-only">Buscar conversas e participantes</span>
            <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Buscar conversa ou pessoa" />
          </label>
          <div className="messages-conversation-list">
            {isListLoading && conversations.length === 0 ? (
              <div className="messages-skeleton-list" aria-label="Carregando conversas">
                {[1, 2, 3, 4].map((item) => <div className="messages-skeleton-row" key={item}><span /><div><i /><i /></div></div>)}
              </div>
            ) : listError ? (
              <div className="messages-inline-state messages-inline-state--error"><strong>Conversas indisponíveis</strong><p>{listError}</p><button type="button" onClick={reload}>Tentar novamente</button></div>
            ) : conversations.length === 0 ? (
              <div className="messages-inline-state"><strong>Nenhuma conversa encontrada</strong><p>{search ? "Revise a busca ou limpe o filtro." : "Crie uma conversa vinculada à operação para começar."}</p>{!search && <button type="button" onClick={() => void openCreateConversation()}>Criar conversa</button>}</div>
            ) : conversations.map((item) => (
              <button key={item.id} type="button" className={`messages-conversation-row ${item.id === selectedConversationId ? "is-active" : ""}`} onClick={() => selectConversation(item.id)}>
                <span className={`messages-avatar messages-avatar--${item.tipo.toLowerCase()}`}>{participantInitials(conversationDisplayName(item))}</span>
                <span className="messages-conversation-copy">
                  <span className="messages-conversation-line"><strong>{conversationDisplayName(item)}</strong><time>{formatConversationTime(item.ultimaMensagemEm ?? item.ultimaAtividadeEm)}</time></span>
                  <span className="messages-conversation-line messages-conversation-preview"><span>{item.ultimaMensagemPrevia || (item.status === "ARQUIVADA" ? "Conversa arquivada" : "Sem mensagens ainda")}</span>{item.naoLidas > 0 && <b aria-label={`${item.naoLidas} mensagens não lidas`}>{item.naoLidas > 99 ? "99+" : item.naoLidas}</b>}</span>
                  {(item.obraNome || item.equipeNome) && <span className="messages-context-line"><i />{item.equipeNome || item.obraNome}</span>}
                </span>
              </button>
            ))}
          </div>
        </aside>

        <section className="messages-thread" aria-label="Histórico da conversa">
          {!selectedConversationId || !conversation ? (
            <div className="messages-empty-thread"><div className="messages-empty-mark"><span /><span /><span /></div><h2>Escolha uma conversa</h2><p>Mensagens e anexos autorizados permanecem neste dispositivo para consulta offline.</p></div>
          ) : (
            <>
              <header className="messages-thread-header">
                <button type="button" className="messages-icon-button messages-mobile-back" onClick={() => setSearchParams({})} aria-label="Voltar às conversas"><Icon name="back" /></button>
                <span className={`messages-avatar messages-avatar--${conversation.tipo.toLowerCase()}`}>{participantInitials(conversationDisplayName(conversation))}</span>
                <div className="messages-thread-heading"><h2>{conversationDisplayName(conversation)}</h2><p>{participants.filter((participant) => participant.status === "ATIVO").map((participant) => participant.colaboradorNome).filter(Boolean).join(", ") || "Participantes autorizados"}</p></div>
                <button type="button" className="messages-icon-button" onClick={reload} aria-label="Atualizar conversa" title="Atualizar conversa"><Icon name="refresh" /></button>
              </header>
              {(conversation.obraNome || conversation.equipeNome) && (
                <div className="messages-context-rail"><span>Contexto rastreável</span>{conversation.obraNome && <button type="button" onClick={() => window.location.assign(`/obras?obra=${encodeURIComponent(conversation.obraId ?? "")}`)}>Obra · {conversation.obraNome}</button>}{conversation.equipeNome && <button type="button" onClick={() => window.location.assign(`/equipes?equipe=${encodeURIComponent(conversation.equipeId ?? "")}`)}>Equipe · {conversation.equipeNome}</button>}</div>
              )}
              {threadError && <div className="messages-thread-alert" role="alert">{threadError}<button type="button" onClick={reload}>Tentar novamente</button></div>}
              <div className="messages-message-list" ref={messageListRef}>
                {hasMore && <button type="button" className="messages-load-older" onClick={() => void loadOlderMessages()} disabled={isThreadLoading}>Carregar mensagens anteriores</button>}
                {isThreadLoading && thread.length === 0 ? (
                  <div className="messages-bubble-skeleton"><span /><span /><span /></div>
                ) : thread.length === 0 ? (
                  <div className="messages-thread-start"><strong>Esta conversa começa aqui</strong><p>Registre decisões, evidências e atualizações da operação.</p></div>
                ) : groupedMessages.map((group) => (
                  <section className="messages-day-group" key={group.key}>
                    <div className="messages-day-divider"><span>{group.label}</span></div>
                    {group.messages.map((message) => {
                      const item = threadById.get(message.id);
                      const outgoing = message.remetenteId === session?.colaboradorId || (!message.remetenteId && message.clientMessageId);
                      const state = messageDeliveryLabel(message);
                      return (
                        <article key={message.id} className={`messages-bubble-row ${outgoing ? "is-outgoing" : "is-incoming"}`}>
                          <div className={`messages-bubble ${message.syncStatus === "ERROR" ? "has-error" : ""}`}>
                            {!outgoing && <strong className="messages-sender">{message.remetenteNome || "Colaborador"}</strong>}
                            {message.texto && <p>{message.texto}</p>}
                            {item?.references.length ? <div className="messages-reference-list">{item.references.map((reference) => <span key={reference.id}>{reference.tipoObjeto} · {reference.objetoId.slice(0, 8)}</span>)}</div> : null}
                            {item?.attachments.length ? (
                              <div className="messages-attachment-list">{item.attachments.map((attachment) => (
                                <button type="button" key={attachment.id} onClick={() => void downloadAttachment(attachment)} disabled={!attachment.arquivo && attachment.status !== "DISPONIVEL"}>
                                  <span className="messages-file-icon"><Icon name="file" /></span><span><strong>{attachment.nomeOriginal}</strong><small>{formatFileSize(attachment.tamanhoBytes)} · {attachment.syncStatus === "UPLOADING" ? "Enviando" : attachment.syncStatus === "PENDING_UPLOAD" || attachment.syncStatus === "WAITING_MESSAGE" ? "Pendente" : attachment.status === "FALHOU" ? "Falha" : "Disponível"}</small></span>
                                </button>
                              ))}</div>
                            ) : null}
                            <footer><time>{formatTime(message.enviadaClienteEm)}</time>{outgoing && <span className={`messages-delivery messages-delivery--${message.syncStatus.toLowerCase()}`}>{state}</span>}</footer>
                            {message.syncStatus === "ERROR" && <button type="button" className="messages-retry" onClick={() => void retryMessage(message.id)}>Tentar novamente</button>}
                          </div>
                        </article>
                      );
                    })}
                  </section>
                ))}
              </div>
              <form className="messages-composer" onSubmit={(event) => void sendMessage(event)}>
                {files.length > 0 && <div className="messages-file-queue">{files.map((file, index) => <span key={`${file.name}-${index}`}>{file.name}<button type="button" aria-label={`Remover ${file.name}`} onClick={() => setFiles((current) => current.filter((_, itemIndex) => itemIndex !== index))}>×</button></span>)}</div>}
                {sendError && <p className="messages-composer-error" role="alert">{sendError}</p>}
                <div className="messages-composer-row">
                  <input ref={fileInputRef} className="sr-only" type="file" multiple accept={ACCEPTED_ATTACHMENT_TYPES.join(",")} onChange={handleFileChange} />
                  <button type="button" className="messages-icon-button messages-attach-button" onClick={() => fileInputRef.current?.click()} aria-label="Anexar arquivo" title="Anexar arquivo"><Icon name="file" /></button>
                  <textarea value={draft} onChange={(event) => setDraft(event.target.value)} onKeyDown={handleComposerKeyDown} rows={1} placeholder={conversation.status === "ARQUIVADA" ? "Conversa arquivada" : navigator.onLine ? "Escreva uma mensagem" : "Escreva — será enviada quando a conexão voltar"} disabled={conversation.status === "ARQUIVADA"} aria-label="Mensagem" />
                  <button type="submit" className="messages-send-button" disabled={conversation.status === "ARQUIVADA" || isSending || (!draft.trim() && files.length === 0)} aria-label="Enviar mensagem"><Icon name="send" /></button>
                </div>
              </form>
            </>
          )}
        </section>
      </main>

      {isCreateOpen && (
        <div className="messages-modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setIsCreateOpen(false); }}>
          <section className="messages-modal" role="dialog" aria-modal="true" aria-labelledby="new-conversation-title">
            <header><div><p className="messages-kicker">Novo canal operacional</p><h2 id="new-conversation-title">Criar conversa</h2></div><button type="button" onClick={() => setIsCreateOpen(false)} aria-label="Fechar">×</button></header>
            <form onSubmit={(event) => void submitNewConversation(event)}>
              <label>Tipo<select value={newConversation.tipo} onChange={(event) => setNewConversation((current) => ({ ...current, tipo: event.target.value as NewConversationForm["tipo"], participanteIds: [] }))}><option value="OBRA">Conversa da obra</option><option value="DIRETA">Conversa direta</option><option value="GRUPO">Grupo operacional</option></select></label>
              <label>Obra<select value={newConversation.obraId} onChange={(event) => void loadCollaboratorsForWorksite(event.target.value)}><option value="">{isAlfa(session) ? "Sem obra vinculada" : "Escolha uma obra"}</option>{obras.map((obra) => <option value={obra.id} key={obra.id}>{obra.nome}</option>)}</select></label>
              <label>Título <span>opcional</span><input value={newConversation.titulo} maxLength={160} onChange={(event) => setNewConversation((current) => ({ ...current, titulo: event.target.value }))} placeholder="Ex.: Liberação da frente norte" /></label>
              {newConversation.tipo !== "OBRA" && <fieldset><legend>{newConversation.tipo === "DIRETA" ? "Pessoa" : "Participantes"}</legend>{collaborators.length === 0 ? <p>Escolha uma obra para carregar as pessoas autorizadas.</p> : collaborators.map((collaborator) => { const checked = newConversation.participanteIds.includes(collaborator.id); return <label className="messages-participant-option" key={collaborator.id}><input type={newConversation.tipo === "DIRETA" ? "radio" : "checkbox"} name="participants" checked={checked} onChange={() => setNewConversation((current) => ({ ...current, participanteIds: current.tipo === "DIRETA" ? [collaborator.id] : checked ? current.participanteIds.filter((id) => id !== collaborator.id) : [...current.participanteIds, collaborator.id] }))} /><span className="messages-avatar">{participantInitials(collaborator.nome)}</span><span><strong>{collaborator.nome}</strong><small>{collaborator.nomePerfil || "Colaborador"}</small></span></label>; })}</fieldset>}
              {createError && <p className="messages-modal-error" role="alert">{createError}</p>}
              <footer><button type="button" className="secondary-button" onClick={() => setIsCreateOpen(false)}>Cancelar</button><button type="submit" className="primary-button" disabled={isCreating}>{isCreating ? "Criando…" : "Criar conversa"}</button></footer>
            </form>
          </section>
        </div>
      )}
    </CortexShell>
  );
}
