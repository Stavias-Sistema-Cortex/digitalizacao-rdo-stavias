import type { ReactNode } from "react";

import type {
  ConversaLocalRecord,
  MensagemAnexoLocalRecord,
  MensagemSyncStatus,
} from "../../../lib/db/db.types";
import {
  conversationInitials,
  formatClock,
  formatFileSize,
  formatRelativeTime,
  initials,
} from "../mensagensFormat";
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
          {conversationInitials(props.title)}
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
          {!mine && showAuthorName ? (
            <span className="mensagem-caption-nome">{message.autorNome}</span>
          ) : null}
          <time
            dateTime={message.criadaNoClienteEm}
            title={formatClock(message.criadaNoClienteEm)}
          >
            {formatRelativeTime(message.criadaNoClienteEm, now)}
          </time>
        </p>
      ) : null}
      <div className="mensagem-linha">
        {mine ? null : (
          <span
            className={`mensagem-avatar-mini${
              startsRun ? "" : " mensagem-avatar-mini--vazio"
            }`}
            aria-hidden="true"
          >
            {startsRun ? initials(message.autorNome) : ""}
          </span>
        )}
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
