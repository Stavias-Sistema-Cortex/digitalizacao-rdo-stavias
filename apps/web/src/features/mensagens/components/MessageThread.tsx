import { useEffect, useRef, type ReactNode } from "react";

import type {
  ConversaLocalRecord,
  MensagemAnexoLocalRecord,
  MensagemSyncStatus,
} from "../../../lib/db/db.types";
import {
  formatClock,
  formatFileSize,
  formatRelativeTime,
} from "../mensagensFormat";
import { mensagemStatusLabel } from "../mensagensQueue";
import type { MensagemComAnexos } from "../mensagensRepository";
import type { MessageTimelineEntry } from "../mensagensView";
import { ConversationAvatar } from "./Avatar";
import {
  IconCheckDouble,
  IconChevronLeft,
  IconClock,
  IconFile,
  IconInfo,
  IconWarning,
} from "./icons";

export interface MessageThreadProps {
  conversation: ConversaLocalRecord | null;
  title: string;
  scope: string;
  participantCount: number;
  timeline: MessageTimelineEntry<MensagemComAnexos>[];
  hasMessages: boolean;
  currentUserId: string;
  isGroup: boolean;
  now: Date;
  infoVisible: boolean;
  composer: ReactNode;
  onBack: () => void;
  onOpenInfo: () => void;
  onOpenAttachment: (attachment: MensagemAnexoLocalRecord) => Promise<void>;
  onRetry: (messageId: string) => Promise<void>;
}

export function MessageThread(props: MessageThreadProps) {
  const listRef = useRef<HTMLOListElement>(null);
  const conversationId = props.conversation?.id ?? null;
  const messageCount = props.timeline.length;

  /* A lista rola por dentro de uma altura fixa, então ela abre no topo:
     sem isto a conversa aparece na mensagem mais antiga. */
  useEffect(() => {
    const el = listRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }, [conversationId, messageCount]);

  if (!props.conversation) {
    return (
      <section className="mensagens-thread" aria-live="polite">
        <div className="mensagens-thread-empty">
          <h2>Abra uma conversa</h2>
          <p>
            O histórico autorizado fica neste aparelho: dá para ler e responder
            mesmo sem sinal.
          </p>
        </div>
      </section>
    );
  }

  return (
    <section className="mensagens-thread" aria-live="polite">
      <header className="mensagens-thread-header">
        <button
          type="button"
          className="mensagens-mobile-back"
          onClick={props.onBack}
          aria-label="Voltar para conversas"
        >
          <IconChevronLeft />
        </button>
        <ConversationAvatar
          conversation={props.conversation}
          currentUserId={props.currentUserId}
        />
        <div className="mensagens-thread-heading">
          <h2>{props.title}</h2>
          <p>
            {props.scope} · {props.participantCount} participantes
          </p>
        </div>
        <button
          type="button"
          className="mensagens-info-btn"
          onClick={props.onOpenInfo}
          aria-label={
            props.infoVisible ? "Ocultar contexto da conversa" : "Ver contexto da conversa"
          }
          aria-expanded={props.infoVisible}
        >
          <IconInfo />
        </button>
      </header>

      <ol className="mensagens-list" ref={listRef}>
        {!props.hasMessages ? (
          <li className="mensagens-empty">Nenhuma mensagem nesta conversa.</li>
        ) : (
          props.timeline.map((entry) => entry.kind === "date" ? (
            <li className="mensagens-date-separator" key={entry.key}>
              <span>{entry.label}</span>
            </li>
          ) : (
            <MessageItem
              key={entry.key}
              message={entry.message}
              startsRun={entry.startsRun}
              mine={entry.message.autorId === props.currentUserId}
              showAuthorName={props.isGroup}
              now={props.now}
              onOpenAttachment={props.onOpenAttachment}
              onRetry={props.onRetry}
            />
          ))
        )}
      </ol>

      {props.composer}
    </section>
  );
}

function MessageItem({
  message,
  startsRun,
  mine,
  showAuthorName,
  now,
  onOpenAttachment,
  onRetry,
}: {
  message: MensagemComAnexos;
  startsRun: boolean;
  mine: boolean;
  showAuthorName: boolean;
  now: Date;
  onOpenAttachment: (attachment: MensagemAnexoLocalRecord) => Promise<void>;
  onRetry: (messageId: string) => Promise<void>;
}) {
  const failed = message.syncStatus === "FALHOU";
  // Ainda no aparelho: a bolha fica vazada até o servidor confirmar.
  const pending = mine && !failed && message.syncStatus !== "SINCRONIZADO";
  const bubbleClass = [
    "mensagem-bubble",
    mine ? "mensagem-bubble--mine" : "mensagem-bubble--in",
    pending ? "mensagem-bubble--pendente" : "",
    failed ? "mensagem-bubble--falhou" : "",
  ]
    .filter(Boolean)
    .join(" ");
  return (
    <li
      className={`mensagem-item${mine ? " mensagem-item--mine" : ""}${
        startsRun ? " mensagem-item--lead" : ""
      }`}
    >
      {startsRun ? (
        <p className="mensagem-caption">
          {/* A hora abre o registro, como em livro de ocorrências; o tempo
              relativo continua disponível na dica do próprio horário. */}
          <time
            dateTime={message.criadaNoClienteEm}
            title={formatRelativeTime(message.criadaNoClienteEm, now)}
          >
            {formatClock(message.criadaNoClienteEm)}
          </time>
          {mine ? (
            <span className="mensagem-caption-nome">Você</span>
          ) : showAuthorName ? (
            <span className="mensagem-caption-nome">{message.autorNome}</span>
          ) : null}
        </p>
      ) : null}
      <div className="mensagem-linha">
        <div className={bubbleClass}>
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
          {mine ? (
            <span className="mensagem-meta">
              <MessageTick status={message.syncStatus} />
            </span>
          ) : null}
          {failed ? (
            <div className="mensagem-retry">
              <span>Não saiu deste aparelho</span>
              <button type="button" onClick={() => void onRetry(message.id)}>
                Tentar de novo
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
