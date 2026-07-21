import type { FormEvent } from "react";

import type { ConversaLocalRecord } from "../../../lib/db/db.types";
import type { MensagemComAnexos } from "../mensagensRepository";
import {
  conversationName,
  conversationScope,
  type ConversationPreview,
} from "../mensagensView";

export interface ConversationsPaneProps {
  loading: boolean;
  conversations: ConversaLocalRecord[];
  previews: Record<string, ConversationPreview>;
  selectedId: string | null;
  currentUserId: string;
  isOnline: boolean;
  search: string;
  searchResults: MensagemComAnexos[] | null;
  onSearchChange: (value: string) => void;
  onSearchSubmit: (event: FormEvent) => void;
  onCloseSearch: () => void;
  onSelect: (id: string) => void;
  onChooseSearchResult: (message: MensagemComAnexos) => void;
}

export function ConversationsPane(props: ConversationsPaneProps) {
  return (
    <aside className="mensagens-conversations">
      <header className="mensagens-pane-heading">
        <strong>Conversas</strong>
        <span>{props.isOnline ? "Online" : "Offline"}</span>
      </header>
      <form className="mensagens-search" onSubmit={props.onSearchSubmit}>
        <label htmlFor="mensagens-search">Buscar no histórico</label>
        <div>
          <input
            id="mensagens-search"
            value={props.search}
            onChange={(event) => props.onSearchChange(event.target.value)}
            placeholder="Mensagem, medição, ocorrência…"
          />
          <button type="submit" aria-label="Buscar mensagens">
            Buscar
          </button>
        </div>
      </form>

      {props.searchResults ? (
        <SearchResults
          results={props.searchResults}
          conversations={props.conversations}
          onChoose={props.onChooseSearchResult}
          onClose={props.onCloseSearch}
        />
      ) : (
        <ConversationList
          loading={props.loading}
          conversations={props.conversations}
          selectedId={props.selectedId}
          currentUserId={props.currentUserId}
          previews={props.previews}
          onSelect={props.onSelect}
        />
      )}
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
