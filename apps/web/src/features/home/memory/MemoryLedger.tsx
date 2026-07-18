import { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";

import { TraceReference } from "../../../components/institutional/TraceReference";
import type { ObraLocalRecord } from "../../../lib/db/db.types";
import type {
  MemoryConflictReviewRecord,
  MemoryEvent,
  MemoryFilters,
} from "./memory.types";
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
  const requestedEventId = search.get("event")?.trim() || null;
  const [expandedIds, setExpandedIds] = useState<Set<string>>(
    () => requestedEventId ? new Set([requestedEventId]) : new Set(),
  );
  const locatedEventIdRef = useRef<string | null>(null);
  const filters = useMemo(
    () => filtersFromSearch(search),
    [search],
  );
  const ledger = useMemoryLedger(filters);

  useEffect(() => {
    if (!requestedEventId) {
      locatedEventIdRef.current = null;
      return;
    }
    if (
      locatedEventIdRef.current === requestedEventId ||
      !ledger.events.some((event) => event.id === requestedEventId)
    ) {
      return;
    }

    const target = document.getElementById(
      memoryEventAnchorId(requestedEventId),
    );
    if (!target) {
      return;
    }

    target.scrollIntoView({ block: "center" });
    locatedEventIdRef.current = requestedEventId;
  }, [ledger.events, requestedEventId]);

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
          <span className="home-section-index">Rastreio ontológico</span>
          <h2>Memória operacional</h2>
          <p>
            Registro técnico de eventos, identificadores e estados de
            sincronização no escopo autorizado.
          </p>
        </div>
        <button type="button" onClick={ledger.reload}>
          Atualizar registro
        </button>
      </header>

      <section className="memory-coverage" data-mode={ledger.coverage.mode}>
        <div>
          <strong>{ledger.coverage.label}</strong>
          <span>{ledger.coverage.detail}</span>
        </div>
      </section>

      <MemoryReviewRegister
        isReviewLoading={ledger.isReviewLoading}
        reviewError={ledger.reviewError}
        reviewRecords={ledger.reviewRecords}
      />

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
          <div>
            <span>Registro técnico</span>
            <strong>{ledger.events.length} entradas neste recorte</strong>
          </div>
          <span>Selecione um evento para inspecionar a alteração</span>
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
          <div className="memory-table-scroll">
            <table className="memory-ledger-table">
              <thead>
                <tr>
                  <th scope="col">ID do evento</th>
                  <th scope="col">Horário</th>
                  <th scope="col">Ator</th>
                  <th scope="col">Dispositivo</th>
                  <th scope="col">Entidade</th>
                  <th scope="col">Ação</th>
                  <th scope="col">Origem</th>
                  <th scope="col">Resultado</th>
                </tr>
              </thead>
              <tbody>
                {ledger.events.map((event) => (
                  <MemoryLedgerRow
                    key={`${event.sourceKind}:${event.id}`}
                    event={event}
                    expanded={expandedIds.has(event.id)}
                    onToggle={() => toggleExpanded(event.id)}
                  />
                ))}
              </tbody>
            </table>
          </div>
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

function MemoryReviewRegister({
  isReviewLoading,
  reviewError,
  reviewRecords,
}: {
  isReviewLoading: boolean;
  reviewError: string | null;
  reviewRecords: MemoryConflictReviewRecord[];
}) {
  const reviewCount = isReviewLoading ? "—" : reviewError ? "?" : reviewRecords.length;

  return (
    <section
      className="memory-review-register"
      aria-label="Registros que exigem revisão"
    >
      <header className="memory-review-register__heading">
        <div>
          <span>Conflitos persistidos</span>
          <h3>Revisão necessária</h3>
          <p>
            Itens abaixo são derivados da fila local com conflitos de campo
            preservados para decisão humana.
          </p>
        </div>
        <strong
          aria-label={isReviewLoading
            ? "Fila em verificação"
            : reviewError ? "Fila indisponível" : undefined}
        >
          {reviewCount}
        </strong>
      </header>

      {isReviewLoading ? (
        <p className="memory-review-register__empty" role="status">
          Consultando a fila local de revisão…
        </p>
      ) : reviewError ? (
        <p className="memory-notice memory-notice--error" role="alert">
          <strong>Fila local indisponível</strong>
          <span>
            Não foi possível verificar a fila local de itens que exigem revisão.
          </span>
        </p>
      ) : reviewRecords.length === 0 ? (
        <p className="memory-review-register__empty">
          Nenhum conflito de campo persistido exige revisão neste recorte.
        </p>
      ) : (
        <div className="memory-review-table-scroll">
          <table className="memory-review-table">
            <thead>
              <tr>
                <th scope="col">Evento</th>
                <th scope="col">Atualizado</th>
                <th scope="col">Ator</th>
                <th scope="col">Dispositivo</th>
                <th scope="col">Entidade</th>
                <th scope="col">Operação</th>
                <th scope="col">Campos em conflito</th>
              </tr>
            </thead>
            <tbody>
              {reviewRecords.map((record) => (
                <tr key={record.clientMutationId}>
                  <td>
                    <TraceReference
                      entityId={record.entity.id}
                      eventId={record.eventId}
                      href={`#${memoryEventAnchorId(record.eventId)}`}
                    />
                  </td>
                  <td>{formatDateTime(record.updatedAt ?? record.occurredAt)}</td>
                  <td>{record.actorName ?? record.actorId ?? "Não informado"}</td>
                  <td>{record.deviceId ?? "Não informado"}</td>
                  <td>{formatEntity(record.entity)}</td>
                  <td>{record.operation}</td>
                  <td>{conflictFields(record)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
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
  const result = eventResult(event);

  return (
    <>
      <tr id={memoryEventAnchorId(event.id)} className="memory-ledger-table__row">
        <td data-label="ID do evento">
          <button
            type="button"
            className="memory-event-toggle"
            aria-expanded={expanded}
            onClick={onToggle}
          >
            <span>{event.id}</span>
            <small>
              {event.sourceKind === "DEVICE"
                ? "Registro local"
                : `Commit #${event.commitSeq ?? "—"}`}
            </small>
          </button>
        </td>
        <td data-label="Horário">
          <time dateTime={event.occurredAt ?? undefined}>
            {formatDateTime(event.occurredAt)}
          </time>
        </td>
        <td data-label="Ator">{event.actorLabel}</td>
        <td data-label="Dispositivo">{eventDevice(event)}</td>
        <td data-label="Entidade">{entityDescription(event)}</td>
        <td data-label="Ação">{memoryEventLabel(event.type)}</td>
        <td data-label="Origem">{eventOrigin(event)}</td>
        <td
          className="memory-ledger-table__result"
          data-result={result}
          data-label="Resultado"
        >
          {result}
        </td>
      </tr>
      {expanded ? (
        <tr className="memory-ledger-table__details-row">
          <td colSpan={8}>
            <div className="memory-entry__details">
              <section>
                <h3>Alteração de estado</h3>
                {diffs.length === 0 ? (
                  <p className="memory-entry__muted">
                    O evento não informou um estado anterior e posterior comparável.
                  </p>
                ) : (
                  <div className="memory-diff">
                    <div className="memory-diff__head">
                      <span>Campo</span>
                      <span>Antes</span>
                      <span>Depois</span>
                    </div>
                    {diffs.map((diff) => (
                      <div key={diff.field} className="memory-diff__row">
                        <strong>{humanizeField(diff.field)}</strong>
                        <span>{formatValue(diff.previous)}</span>
                        <span>{formatValue(diff.next)}</span>
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
                  <div><dt>Origem</dt><dd>{eventOrigin(event)}</dd></div>
                  <div><dt>Dispositivo</dt><dd>{eventDevice(event)}</dd></div>
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
          </td>
        </tr>
      ) : null}
    </>
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

function conflictFields(record: MemoryConflictReviewRecord): string {
  return Object.keys(record.conflicts)
    .sort((left, right) => left.localeCompare(right, "pt-BR"))
    .join(", ");
}

function entityDescription(event: MemoryEvent): string {
  return formatEntity(event.principalEntity);
}

function formatEntity(entity: MemoryEvent["principalEntity"]): string {
  return `${entity.type} · ${entity.name ?? entity.id}`;
}

function eventDevice(event: MemoryEvent): string {
  return event.deviceId ?? (
    event.sourceKind === "DEVICE" ? "Este dispositivo" : "Não informado"
  );
}

function eventOrigin(event: MemoryEvent): string {
  return event.source ?? event.origin ?? (
    event.sourceKind === "DEVICE" ? "Este dispositivo" : "Não informada"
  );
}

function eventResult(event: MemoryEvent): string {
  return event.result ?? event.syncStatus ?? (
    event.sourceKind === "DEVICE" ? "LOCAL" : "REGISTRADO"
  );
}

function memoryEventAnchorId(eventId: string): string {
  return `memory-event-${encodeURIComponent(eventId)}`;
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
