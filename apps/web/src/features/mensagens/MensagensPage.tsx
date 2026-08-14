import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { useSearchParams } from "react-router";

import { CortexShell } from "../../components/shell/CortexShell";
import { CortexPageHeader } from "../../components/header/CortexPageHeader";
import type {
  ConversaLocalRecord,
  MensagemAnexoLocalRecord,
  ObraLocalRecord,
  PreferenciaDeConversaLocal,
} from "../../lib/db/db.types";
import { listObrasLocais } from "../../lib/db/obraLocalRepository";
import { syncNow } from "../../lib/sync/syncEngine";
import { isSyncLeaseContentionError } from "../../lib/sync/syncLeaseContention";
import { useSyncStatus } from "../../lib/sync/useSyncStatus";
import { getSession, hasOnlineSession, isAlfa } from "../auth/authSession";
import { ConversationInfoPane } from "./components/ConversationInfoPane";
import { ConversationsPane } from "./components/ConversationsPane";
import { CreateConversationDialog } from "./components/CreateConversationDialog";
import { MessageComposer } from "./components/MessageComposer";
import { MessageThread } from "./components/MessageThread";
import {
  arquivarConversaApi,
  limparConversaApi,
} from "./mensagensApi";
import { messageFrom } from "./mensagensFormat";
import {
  downloadMessageAttachmentApi,
  searchMessagesApi,
} from "./mensagensApi";
import {
  conversaAindaNaoExisteNoServidor,
  refreshConversationHistory,
  refreshConversationList,
} from "./mensagensHydration";
import {
  gravarPreferenciaDaConversa,
  listarPreferenciasDeConversa,
  localAttachmentBlob,
  listLocalConversationPreviews,
  listLocalConversations,
  listLocalMessages,
  MESSAGES_CHANGED_EVENT,
  queueMessage,
  retryMessage,
  searchLocalMessages,
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
type ConversationLoadState = "loading" | "ready" | "failed";

export function MensagensPage() {
  const session = getSession();
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedConversationId = searchParams.get("conversa");
  const [conversations, setConversations] = useState<ConversaLocalRecord[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [messages, setMessages] = useState<MensagemComAnexos[]>([]);
  const [previews, setPreviews] = useState<Record<string, ConversationPreview>>({});
  const [worksites, setWorksites] = useState<ObraLocalRecord[]>([]);
  const [conversationLoadState, setConversationLoadState] =
    useState<ConversationLoadState>("loading");
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
  /*
   * A gaveta de arquivadas é lida direto do servidor e nunca gravada como
   * retrato autoritativo: gravá-la apagaria do aparelho tudo o que NÃO está
   * arquivado, que é justamente a lista principal.
   */
  const [arquivadas, setArquivadas] = useState<
    ConversaLocalRecord[] | null
  >(null);
  const [arrumando, setArrumando] = useState(false);
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
    const [localConversations, localPreviews, localWorksites, preferencias] =
      await Promise.all([
        listLocalConversations(),
        listLocalConversationPreviews(),
        listObrasLocais({ includeArchived: true }),
        listarPreferenciasDeConversa(),
      ]);
    // Quem esta pessoa tirou da própria lista sai daqui, e não da resposta do
    // servidor: assim o gesto vale no aparelho, sem rede, e a conversa segue
    // inteira para quem estava junto.
    setConversations(
      localConversations.filter(
        (conversa) => !preferencias.get(conversa.id)?.arquivadoEm,
      ),
    );
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
        if (!cancelled) {
          setConversationLoadState("ready");
        }
      } catch (cause: unknown) {
        if (!cancelled) {
          setError(messageFrom(cause));
          setConversationLoadState("failed");
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
          /*
           * A conversa aberta agora pode ser mais nova que o servidor: quem a
           * criou neste aparelho a vê na lista, abre e escreve, tudo local, e a
           * fila leva a criação quando puder. Pedir o histórico dela antes
           * disso devolve 404 — que é a resposta certa para uma pergunta que
           * ainda não faz sentido, e não uma falha a acusar.
           *
           * Era isso que acendia "Not Found", em inglês e em vermelho, sobre
           * uma tela em que tudo funcionava: a conversa estava lá, a pessoa
           * conseguia mandar mensagem, e a tarja dizia que algo tinha quebrado.
           * Sem histórico remoto não há o que baixar, e o que já está no
           * aparelho continua na tela.
           */
          try {
            await refreshConversationHistory(selectedId);
            await loadMessages(selectedId);
          } catch (cause: unknown) {
            if (!conversaAindaNaoExisteNoServidor(cause)) throw cause;
          }
        }
        // Carregou: o que quer que estivesse aceso já não descreve esta tela.
        if (!cancelled) setError("");
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
    async function changed() {
      setConversationLoadState("loading");
      try {
        await loadLocal();
        await loadMessages(selectedId);
        setConversationLoadState("ready");
      } catch (cause: unknown) {
        setError(messageFrom(cause));
        setConversationLoadState("failed");
      }
    }
    const handleChanged = () => void changed();
    window.addEventListener(MESSAGES_CHANGED_EVENT, handleChanged);
    return () => window.removeEventListener(MESSAGES_CHANGED_EVENT, handleChanged);
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
      // Outra aba sincronizando não é falha desta: o trabalho está sendo feito.
      if (!isSyncLeaseContentionError(cause)) {
        setError(messageFrom(cause));
      }
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
    if (
      conversationLoadState === "loading" ||
      (conversationLoadState === "failed" && conversations.length === 0)
    ) {
      return;
    }
    const query = search.trim();
    if (!query) {
      setSearchResults(null);
      return;
    }
    if (conversationLoadState === "failed") {
      try {
        setSearchResults(await searchLocalMessages(query));
      } catch (cause: unknown) {
        setError(messageFrom(cause));
      }
      return;
    }
    setError("");
    if (conversations.length === 0) {
      setSearchResults([]);
      return;
    }
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
      // A mensagem já voltou para a fila antes do envio. Dizer que a
      // retentativa falhou porque outra aba assumiu a sincronização nega um
      // gesto que surtiu efeito, e convida a repeti-lo à toa.
      if (!isSyncLeaseContentionError(cause)) {
        setError(messageFrom(cause));
      }
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

  /*
   * Arrumar a caixa é gesto de leitor: qualquer pessoa faz em qualquer conversa
   * que alcance, e o efeito para nos olhos dela. Chamada direta, fora da fila
   * do aparelho — sem rede o gesto falha na hora e a tela diz, em vez de a
   * conversa sumir aqui e reaparecer depois sem explicação.
   */
  const arrumarCaixa = useCallback(
    async (
      conversaId: string,
      mudanca: Partial<Omit<PreferenciaDeConversaLocal, "conversaId">>,
      enviar: () => Promise<void>,
      aviso: string,
    ) => {
      setArrumando(true);
      setError("");
      try {
        // O aparelho obedece primeiro. A tela lê a preferência local, então o
        // efeito é imediato e vale no modo avião — que é onde metade do
        // Córtex vive.
        await gravarPreferenciaDaConversa(conversaId, {
          ...mudanca,
          pendente: true,
        });
        await loadLocal();
        await loadMessages(selectedId);
        try {
          await enviar();
          await gravarPreferenciaDaConversa(conversaId, { pendente: false });
        } catch (semRede: unknown) {
          // Sem rede o gesto continua valendo aqui e sobe na próxima
          // sincronização. Avisar é honesto; desfazer seria pior.
          setError(
            "Arrumação guardada neste aparelho; ela sobe quando a rede voltar." +
              ` (${messageFrom(semRede)})`,
          );
        }
        await loadLocal();
      } catch (causa: unknown) {
        setError(`${aviso} ${messageFrom(causa)}`);
      } finally {
        setArrumando(false);
      }
    },
    [loadLocal, loadMessages, selectedId],
  );

  /*
   * A gaveta sai do próprio aparelho: quem arquivou sem rede precisa conseguir
   * desarquivar sem rede. Buscá-la no servidor deixaria a saída trancada
   * justamente para quem está em campo.
   */
  const abrirGaveta = useCallback(async () => {
    if (arquivadas !== null) {
      setArquivadas(null);
      return;
    }
    setArrumando(true);
    setError("");
    try {
      const preferencias = await listarPreferenciasDeConversa();
      const guardadas = await listLocalConversations();
      setArquivadas(
        guardadas.filter(
          (conversa) => preferencias.get(conversa.id)?.arquivadoEm,
        ),
      );
    } catch (causa: unknown) {
      setError(`Não foi possível abrir as arquivadas. ${messageFrom(causa)}`);
    } finally {
      setArrumando(false);
    }
  }, [arquivadas]);

  return (
    <CortexShell
      active="mensagens"
      onRefresh={() => void handleRefresh()}
      isRefreshing={refreshing}
    >
      <main className="mensagens-page">
        <CortexPageHeader
          eyebrow="Comunicação"
          title="Mensagens"
          description={`${conversations.length} conversas autorizadas`}
          legacyPrefix="mensagens-header"
          actions={(
            <>
              {selected ? (
                <>
                  <button
                    type="button"
                    className="mensagens-secondary"
                    disabled={arrumando}
                    onClick={() => void arrumarCaixa(
                      selected.id,
                      { limpoAte: new Date().toISOString() },
                      () => limparConversaApi(selected.id, true),
                      "Não foi possível limpar a conversa.",
                    )}
                    title="Esconde o histórico anterior a agora só para você. Nada é apagado: quem estava junto continua vendo tudo."
                  >
                    Limpar conversa
                  </button>
                  <button
                    type="button"
                    className="mensagens-secondary"
                    disabled={arrumando}
                    onClick={() => void arrumarCaixa(
                      selected.id,
                      { arquivadoEm: new Date().toISOString() },
                      () => arquivarConversaApi(selected.id, true),
                      "Não foi possível arquivar a conversa.",
                    )}
                    title="Tira a conversa da sua lista. Ela continua na lista de quem estava junto."
                  >
                    Arquivar
                  </button>
                </>
              ) : null}
              <button
                type="button"
                className="mensagens-secondary"
                disabled={arrumando}
                onClick={() => void abrirGaveta()}
                aria-expanded={arquivadas !== null}
              >
                {arquivadas === null
                  ? "Arquivadas"
                  : "Fechar arquivadas"}
              </button>
              <button
                type="button"
                className="mensagens-primary"
                onClick={() => setShowCreate(true)}
              >
                Nova conversa
              </button>
            </>
          )}
        />

        {error ? (
          <div className="mensagens-alert" role="alert">
            <span>{error}</span>
            <button type="button" onClick={() => setError("")}>
              Fechar
            </button>
          </div>
        ) : null}

        {arquivadas !== null ? (
          <section
            className="mensagens-gaveta"
            aria-label="Conversas que você arquivou"
          >
            <h2>Arquivadas por você</h2>
            <p>
              Estas conversas saíram da sua lista e continuam na lista de quem
              estava junto. Devolvê-las não avisa ninguém.
            </p>
            {arquivadas.length === 0 ? (
              <p className="mensagens-gaveta-vazia">
                Você não arquivou nenhuma conversa.
              </p>
            ) : (
              <ul>
                {arquivadas.map((conversa) => (
                  <li key={conversa.id}>
                    <span>{conversa.titulo || "Conversa sem título"}</span>
                    <button
                      type="button"
                      disabled={arrumando}
                      onClick={() => void arrumarCaixa(
                        conversa.id,
                        { arquivadoEm: null },
                        () => arquivarConversaApi(conversa.id, false),
                        "Não foi possível devolver a conversa à lista.",
                      )}
                    >
                      Devolver à lista
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        ) : null}

        <div className="mensagens-frame" ref={frameRef}>
        <section
          className={`mensagens-workspace mensagens-workspace--${mobilePane}${
            contextOpen ? " mensagens-workspace--drawer-open" : ""
          }${infoCollapsed ? " mensagens-workspace--info-hidden" : ""}`}
          aria-label="Mensagens"
        >
          <ConversationsPane
            loadState={conversationLoadState}
            conversations={conversations}
            previews={previews}
            selectedId={selectedId}
            currentUserId={session?.colaboradorId ?? ""}
            isOnline={snapshot.isOnline}
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
                isOnline={snapshot.isOnline}
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
              // Já está gravada no dispositivo: só relemos e abrimos. Guardá-la
              // como resposta do servidor a marcaria como confirmada por quem
              // ainda nem a viu.
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
