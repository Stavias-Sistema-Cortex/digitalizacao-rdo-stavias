import { useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";

import type { ObraLocalRecord } from "../../../lib/db/db.types";
import type { MemoryEvent, MemoryFilters } from "./memory.types";
import {
  memoryDiffRows,
  memoryEventLabel,
} from "./memoryViewModel";
import { useMemoryLedger } from "./useMemoryLedger";
import "./MemoryLedger.css";

interface MemoryLedgerProps {
  obras: ObraLocalRecord[];
}

const FILTER_FIELDS = [
  "entityType",
  "entityId",
  "obraId",
  "rdoId",
  "actorId",
  "eventType",
  "origin",
  "result",
  "from",
  "to",
] as const;

const EVENT_TYPES = [
  "RDO_CRIADO",
  "RDO_EDITADO",
  "RDO_SINCRONIZADO",
  "RDO_FALHA_SYNC",
  "TAREFA_CRIADA",
  "TAREFA_CONCLUIDA",
  "TAREFA_REABERTA",
  "TAREFA_EXCLUIDA",
  "OBRA_CRIADA",
  "OBRA_ATUALIZADA",
  "EQUIPE_CRIADA",
  "EQUIPE_ATUALIZADA",
  "MEMBRO_EQUIPE_ADICIONADO",
  "MEMBRO_EQUIPE_ENCERRADO",
] as const;

export function MemoryLedger({ obras }: MemoryLedgerProps) {
  const [search, setSearch] = useSearchParams();
  const [expandedIds, setExpandedIds] = useState<Set<string>>(
    () => new Set(),
  );
  const filters = useMemo(
    () => filtersFromSearch(search),
    [search],
  );
  const ledger = useMemoryLedger(filters);

  function setFilter(field: typeof FILTER_FIELDS[number], value: string) {
    const next = new URLSearchParams(search);
    if (value.trim()) {
      next.set(field, value.trim());
    } else {
      next.delete(field);
    }
    next.set("tab", "memory");
    setSearch(next, { replace: true });
  }

  function clearFilters() {
    const next = new URLSearchParams(search);
    for (const field of FILTER_FIELDS) {
      next.delete(field);
    }
    next.set("tab", "memory");
    setSearch(next, { replace: true });
  }

  function toggleExpanded(id: string) {
    setExpandedIds((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  return (
    <div className="memory-ledger">
      <header className="memory-ledger__header">
        <div>
          <span className="home-section-index">02 / Ontologia</span>
          <h2>Memória operacional</h2>
          <p>
            Registro cronológico das alterações que constituem o Córtex.
            Cada entrada preserva ator, entidade, origem e estado conhecido.
          </p>
        </div>
        <button type="button" onClick={ledger.reload}>
          Atualizar registro
        </button>
      </header>

      <section className="memory-coverage" data-mode={ledger.coverage.mode}>
        <span className="memory-coverage__mark" aria-hidden="true" />
        <div>
          <strong>{ledger.coverage.label}</strong>
          <span>{ledger.coverage.detail}</span>
        </div>
      </section>

      <section className="memory-filters" aria-label="Filtros da Memória">
        <div className="memory-filters__heading">
          <div>
            <span>Recorte estrutural</span>
            <strong>Filtrar o registro</strong>
          </div>
          <button type="button" onClick={clearFilters}>Limpar filtros</button>
        </div>
        <div className="memory-filters__grid">
          <label>
            Obra
            <select
              value={filters.obraId ?? ""}
              onChange={(event) => setFilter("obraId", event.target.value)}
            >
              <option value="">Todas no meu escopo</option>
              {obras.map((obra) => (
                <option key={obra.id} value={obra.id}>{obra.nome}</option>
              ))}
            </select>
          </label>
          <label>
            RDO
            <input
              value={filters.rdoId ?? ""}
              placeholder="ID exato do RDO"
              onChange={(event) => setFilter("rdoId", event.target.value)}
            />
          </label>
          <label>
            Alteração
            <select
              value={filters.eventType ?? ""}
              onChange={(event) => setFilter("eventType", event.target.value)}
            >
              <option value="">Todos os tipos</option>
              {EVENT_TYPES.map((type) => (
                <option key={type} value={type}>{memoryEventLabel(type)}</option>
              ))}
            </select>
          </label>
          <label>
            Ator
            <input
              value={filters.actorId ?? ""}
              placeholder="ID exato do responsável"
              onChange={(event) => setFilter("actorId", event.target.value)}
            />
          </label>
          <label>
            Origem
            <select
              value={filters.origin ?? ""}
              onChange={(event) => setFilter("origin", event.target.value)}
            >
              <option value="">Todas</option>
              <option value="ONLINE">Servidor</option>
              <option value="OFFLINE">Dispositivo</option>
              <option value="SYNC">Sincronização</option>
            </select>
          </label>
          <label>
            Resultado
            <select
              value={filters.result ?? ""}
              onChange={(event) => setFilter("result", event.target.value)}
            >
              <option value="">Todos</option>
              <option value="SUCESSO">Sucesso</option>
              <option value="FALHA">Falha</option>
            </select>
          </label>
          <label>
            Desde
            <input
              type="datetime-local"
              value={filters.from ?? ""}
              onChange={(event) => setFilter("from", event.target.value)}
            />
          </label>
          <label>
            Até
            <input
              type="datetime-local"
              value={filters.to ?? ""}
              onChange={(event) => setFilter("to", event.target.value)}
            />
          </label>
        </div>
      </section>

      <section className="memory-register" aria-live="polite">
        <div className="memory-register__heading">
          <span>Registro</span>
          <strong>{ledger.events.length} entradas neste recorte</strong>
        </div>

        {ledger.error ? (
          <div className="memory-notice memory-notice--error" role="alert">
            <strong>Consulta incompleta</strong>
            <span>{ledger.error}</span>
          </div>
        ) : null}

        {ledger.isInitialLoading && ledger.events.length === 0 ? (
          <div className="memory-empty" role="status">
            Lendo os registros deste dispositivo…
          </div>
        ) : ledger.events.length === 0 ? (
          <div className="memory-empty">
            Nenhuma alteração corresponde ao recorte informado.
          </div>
        ) : (
          <ol className="memory-list">
            {ledger.events.map((event) => (
              <MemoryLedgerRow
                key={`${event.sourceKind}:${event.id}`}
                event={event}
                expanded={expandedIds.has(event.id)}
                onToggle={() => toggleExpanded(event.id)}
              />
            ))}
          </ol>
        )}

        {ledger.hasMore ? (
          <button
            className="memory-load-more"
            type="button"
            disabled={ledger.isLoadingMore}
            onClick={() => void ledger.loadMore()}
          >
            {ledger.isLoadingMore ? "Carregando…" : "Carregar registros anteriores"}
          </button>
        ) : null}
      </section>
    </div>
  );
}

function MemoryLedgerRow({
  event,
  expanded,
  onToggle,
}: {
  event: MemoryEvent;
  expanded: boolean;
  onToggle: () => void;
}) {
  const diffs = memoryDiffRows(event);
  const pending = event.sourceKind === "DEVICE";

  return (
    <li className="memory-entry">
      <button
        type="button"
        className="memory-entry__summary"
        aria-expanded={expanded}
        onClick={onToggle}
      >
        <span className="memory-entry__commit">
          {pending ? "LOCAL" : `#${event.commitSeq ?? "—"}`}
        </span>
        <span className="memory-entry__main">
          <strong>{memoryEventLabel(event.type)}</strong>
          <span>
            {event.actorLabel} · {entityDescription(event)}
          </span>
        </span>
        <span className="memory-entry__time">
          {formatDateTime(event.occurredAt)}
        </span>
        <span className={pending
          ? "memory-entry__status is-pending"
          : "memory-entry__status"}
        >
          {pending ? "Commit pendente" : event.result ?? "Registrado"}
        </span>
        <span className="memory-entry__toggle" aria-hidden="true">
          {expanded ? "−" : "+"}
        </span>
      </button>

      {expanded ? (
        <div className="memory-entry__details">
          <section>
            <h3>Alteração de estado</h3>
            {diffs.length === 0 ? (
              <p className="memory-entry__muted">
                O evento não informou um estado anterior e posterior comparável.
              </p>
            ) : (
              <div className="memory-diff" role="table" aria-label="Antes e depois">
                <div className="memory-diff__head" role="row">
                  <span role="columnheader">Campo</span>
                  <span role="columnheader">Antes</span>
                  <span role="columnheader">Depois</span>
                </div>
                {diffs.map((diff) => (
                  <div key={diff.field} className="memory-diff__row" role="row">
                    <strong role="cell">{humanizeField(diff.field)}</strong>
                    <span role="cell">{formatValue(diff.previous)}</span>
                    <span role="cell">{formatValue(diff.next)}</span>
                  </div>
                ))}
              </div>
            )}
          </section>
          <section className="memory-entry__trace">
            <h3>Vínculo técnico</h3>
            <dl>
              <div><dt>Evento</dt><dd>{event.id}</dd></div>
              <div><dt>Entidade</dt><dd>{entityDescription(event)}</dd></div>
              <div><dt>Origem</dt><dd>{event.source ?? event.origin ?? "Não informada"}</dd></div>
              <div><dt>Dispositivo</dt><dd>{event.deviceId ?? (pending ? "Este dispositivo" : "Não informado")}</dd></div>
              <div><dt>Correlação</dt><dd>{event.correlationId ?? "Não informada"}</dd></div>
              <div><dt>Causação</dt><dd>{event.causationId ?? "Não informada"}</dd></div>
            </dl>
          </section>
          {Object.keys(event.payload).length > 0 ? (
            <details className="memory-entry__payload">
              <summary>Payload registrado</summary>
              <pre>{JSON.stringify(event.payload, null, 2)}</pre>
            </details>
          ) : null}
        </div>
      ) : null}
    </li>
  );
}

function filtersFromSearch(search: URLSearchParams): MemoryFilters {
  const filters: MemoryFilters = { limit: 50 };
  for (const field of FILTER_FIELDS) {
    const value = search.get(field)?.trim();
    if (value) filters[field] = value;
  }
  return filters;
}

function entityDescription(event: MemoryEvent): string {
  const name = event.principalEntity.name;
  return `${event.principalEntity.type} · ${name ?? event.principalEntity.id}`;
}

function formatDateTime(value: string | null): string {
  if (!value) return "Horário não informado";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}

function formatValue(value: unknown): string {
  if (value === undefined || value === null || value === "") return "—";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function humanizeField(field: string): string {
  return field
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replaceAll("_", " ")
    .replace(/^./, (letter) => letter.toLocaleUpperCase("pt-BR"));
}
