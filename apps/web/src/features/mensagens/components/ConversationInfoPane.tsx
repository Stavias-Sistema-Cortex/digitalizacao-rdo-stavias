import { useState, type ReactNode } from "react";

import type {
  ConversaLocalRecord,
  MensagemAnexoLocalRecord,
  ObraLocalRecord,
} from "../../../lib/db/db.types";
import {
  conversationInitials,
  formatFileSize,
  formatRelativeTime,
  initials,
} from "../mensagensFormat";
import type { MensagemComAnexos } from "../mensagensRepository";
import { activeParticipant } from "../mensagensView";
import { IconChevronDown, IconChevronLeft, IconClose } from "./icons";

export interface ConversationInfoPaneProps {
  conversation: ConversaLocalRecord | null;
  title: string;
  scope: string;
  messages: MensagemComAnexos[];
  worksites: ObraLocalRecord[];
  now: Date;
  isOnline: boolean;
  lastSyncCompletedAt: string | null;
  onBack: () => void;
  onClose: () => void;
  onOpenAttachment: (attachment: MensagemAnexoLocalRecord) => Promise<void>;
}

export function ConversationInfoPane(props: ConversationInfoPaneProps) {
  const { conversation } = props;
  if (!conversation) {
    return (
      <aside className="mensagens-context">
        <p className="mensagens-list-status">O contexto aparece ao abrir uma conversa.</p>
      </aside>
    );
  }
  const worksite = props.worksites.find((item) => item.id === conversation.obraId);
  const participants = conversation.participantes.filter(activeParticipant);
  const attachments = props.messages.flatMap((message) => message.anexos);

  return (
    <aside className="mensagens-context" aria-label="Contexto da conversa">
      <header>
        <button
          type="button"
          className="mensagens-mobile-back"
          onClick={props.onBack}
          aria-label="Voltar para a conversa"
        >
          <IconChevronLeft />
        </button>
        <div>
          <strong>Contexto</strong>
          <span>{props.scope}</span>
        </div>
        <button
          type="button"
          className="mensagens-drawer-close"
          onClick={props.onClose}
          aria-label="Fechar contexto"
        >
          <IconClose />
        </button>
      </header>

      <div className="mensagens-info-identity">
        <span className="mensagens-info-avatar" aria-hidden="true">
          {conversationInitials(props.title)}
        </span>
        <strong>{props.title}</strong>
        <small>{props.scope}</small>
      </div>

      <InfoSection title="Informação" defaultOpen>
        {conversation.obraId ? (
          <p className="mensagens-info-linha">
            <strong>{worksite?.nome ?? "Obra vinculada"}</strong>
            <small>
              {worksite?.codigoContrato ?? shortIdentifier(conversation.obraId)}
            </small>
          </p>
        ) : null}
        <p className="mensagens-info-linha">
          <strong>{participants.length} participantes</strong>
        </p>
        <p className="mensagens-info-linha">
          <strong>
            {syncLabel(props.isOnline, props.lastSyncCompletedAt, props.now)}
          </strong>
          <small>Última troca com o servidor</small>
        </p>
      </InfoSection>

      <InfoSection title={`Pessoas (${participants.length})`} defaultOpen>
        <ul className="mensagens-context-people">
          {participants.map((participant) => (
            <li key={participant.colaboradorId}>
              <span aria-hidden="true">{initials(participant.nome)}</span>
              <div>
                <strong>{participant.nome}</strong>
                <small>
                  {participant.papel === "ADMIN" ? "Administrador" : "Membro"}
                </small>
              </div>
            </li>
          ))}
        </ul>
      </InfoSection>

      <InfoSection title={`Anexos (${attachments.length})`} defaultOpen={false}>
        {attachments.length === 0 ? (
          <p className="mensagens-info-vazio">Nenhum documento nesta conversa.</p>
        ) : (
          <ul className="mensagens-context-documents">
            {attachments.map((attachment) => (
              <li key={attachment.id}>
                <button
                  type="button"
                  onClick={() => void props.onOpenAttachment(attachment)}
                >
                  <strong>{attachment.nome}</strong>
                  <small>{formatFileSize(attachment.tamanhoBytes)}</small>
                </button>
              </li>
            ))}
          </ul>
        )}
      </InfoSection>
    </aside>
  );
}

function InfoSection({
  title,
  defaultOpen,
  children,
}: {
  title: string;
  defaultOpen: boolean;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <section className="mensagens-info-section">
      <button
        type="button"
        className="mensagens-info-toggle"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        <span>{title}</span>
        <IconChevronDown className={open ? undefined : "mensagens-chevron--fechado"} />
      </button>
      {open ? <div className="mensagens-info-body">{children}</div> : null}
    </section>
  );
}

function syncLabel(
  isOnline: boolean,
  lastSyncCompletedAt: string | null,
  now: Date,
): string {
  if (!isOnline) {
    return "Offline";
  }
  if (!lastSyncCompletedAt) {
    return "Nunca sincronizado";
  }
  return `Sincronizado ${formatRelativeTime(lastSyncCompletedAt, now)}`;
}

function shortIdentifier(value: string): string {
  return value.length > 12 ? `${value.slice(0, 8)}…` : value;
}
