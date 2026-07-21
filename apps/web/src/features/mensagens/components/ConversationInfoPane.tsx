import type {
  ConversaLocalRecord,
  MensagemAnexoLocalRecord,
  ObraLocalRecord,
} from "../../../lib/db/db.types";
import { formatFileSize } from "../mensagensFormat";
import type { MensagemComAnexos } from "../mensagensRepository";
import { activeParticipant, conversationScope } from "../mensagensView";
import { IconChevronLeft, IconClose } from "./icons";

export interface ConversationInfoPaneProps {
  conversation: ConversaLocalRecord | null;
  messages: MensagemComAnexos[];
  worksites: ObraLocalRecord[];
  onBack: () => void;
  onClose: () => void;
  onOpenAttachment: (attachment: MensagemAnexoLocalRecord) => Promise<void>;
}

export function ConversationInfoPane({
  conversation,
  messages,
  worksites,
  onBack,
  onClose,
  onOpenAttachment,
}: ConversationInfoPaneProps) {
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

function shortIdentifier(value: string): string {
  return value.length > 12 ? `${value.slice(0, 8)}…` : value;
}
