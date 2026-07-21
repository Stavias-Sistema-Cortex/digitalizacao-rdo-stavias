import type { ReactNode } from "react";

import type {
  ConversaLocalRecord,
  MensagemAnexoLocalRecord,
  MensagemSyncStatus,
} from "../../../lib/db/db.types";
import { formatClock, formatFileSize } from "../mensagensFormat";
import { mensagemStatusLabel } from "../mensagensQueue";
import type { MensagemComAnexos } from "../mensagensRepository";
import type { MessageTimelineEntry } from "../mensagensView";
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
  composer: ReactNode;
  onBack: () => void;
  onOpenInfo: () => void;
  onOpenAttachment: (attachment: MensagemAnexoLocalRecord) => Promise<void>;
  onRetry: (messageId: string) => Promise<void>;
}

export function MessageThread(props: MessageThreadProps) {
  if (!props.conversation) {
    return (
      <section className="mensagens-thread" aria-live="polite">
        <div className="mensagens-thread-empty">
          <h2>Selecione uma conversa</h2>
          <p>O histórico autorizado será carregado deste dispositivo.</p>
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
        <span className="mensagens-thread-avatar" aria-hidden="true">
          {props.title.slice(0, 1).toUpperCase()}
        </span>
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
          aria-label="Ver contexto da conversa"
        >
          <IconInfo />
        </button>
      </header>

      <ol className="mensagens-list">
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
              showAuthor={entry.startsRun}
              mine={entry.message.autorId === props.currentUserId}
              isGroup={props.isGroup}
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
