import type { ObraLocalRecord } from "../../../lib/db/db.types";
import { operationalEventLabel } from "../eventLabels";
import type { MemoryFilters } from "./memoryApi";
import {
  memoryStatusLabel,
  type MemorySearchDocument,
} from "./memorySearchDocument";
import {
  useMemoryLedger,
  type MemoryLedgerViewModel,
} from "./useMemoryLedger";
import "./MemoryLedger.css";

export function MemoryLedger({ obras }: { obras: ObraLocalRecord[] }) {
  return <MemoryLedgerView ledger={useMemoryLedger()} obras={obras} />;
}

export function MemoryLedgerView({
  ledger,
  obras,
}: {
  ledger: MemoryLedgerViewModel;
  obras: ObraLocalRecord[];
}) {
  const activeFilters = countFilters(ledger.filters);
  return (
    <div className="memory-ledger">
      <header className="memory-ledger__header">
        <div>
          <span className="memory-ledger__eyebrow">Registro ontológico autorizado</span>
          <h2>Memória operacional</h2>
          <p>
            Pesquise todo o histórico armazenado neste dispositivo, inclusive
            alterações locais que ainda não receberam commit do servidor.
          </p>
        </div>
        <button
          type="button"
          className="memory-ledger__refresh"
          onClick={ledger.refresh}
          disabled={ledger.isRefreshing}
        >
          {ledger.isRefreshing ? "Sincronizando…" : "Atualizar Memória"}
        </button>
      </header>

      <section
        className="memory-coverage"
        data-status={ledger.coverage.code}
        role="status"
        aria-live="polite"
      >
        <span className="memory-coverage__signal" aria-hidden="true" />
        <div>
          <strong>{ledger.coverage.label}</strong>
          <span>{ledger.coverage.detail}</span>
        </div>
        <dl>
          <div>
            <dt>Marca d’água</dt>
            <dd>{ledger.metadata ? `Commit ${ledger.metadata.highWaterMark}` : "Não confirmada"}</dd>
          </div>
          <div>
            <dt>Cache autorizado</dt>
            <dd>
              {ledger.metadata
                ? `${ledger.metadata.cachedEventCount} de ${ledger.metadata.authorizedEventCount}`
                : "Somente eventos locais"}
            </dd>
          </div>
          {ledger.metadata?.graph ? (
            <div>
              <dt>Grafo ontológico</dt>
              <dd>{graphCoverageLabel(ledger.metadata.graph)}</dd>
            </div>
          ) : null}
        </dl>
      </section>

      <section className="memory-query">
        <label className="memory-query__search">
          <span>Pesquisa integral</span>
          <input
            type="search"
            aria-label="Pesquisar em toda a Memória armazenada"
            value={ledger.filters.q ?? ""}
            placeholder="Serviço, obra, RDO, entidade, evento ou ID"
            onChange={(event) => ledger.setFilters({ q: event.target.value })}
          />
        </label>
        <details className="memory-query__filters" open={activeFilters > 1}>
          <summary>
            Filtros estruturais
            {activeFilters > 0 ? <span>{activeFilters} ativos</span> : null}
          </summary>
          <fieldset aria-label="Filtros estruturais da Memória">
            <label>
              Obra
              <select
                value={ledger.filters.worksiteId ?? ""}
                onChange={(event) => ledger.setFilters({ worksiteId: event.target.value })}
              >
                <option value="">Todas no escopo</option>
                {obras.map((obra) => (
                  <option key={obra.id} value={obra.id}>{obra.nome}</option>
                ))}
              </select>
            </label>
            <label>
              Tipo de evento
              <input
                value={ledger.filters.eventType ?? ""}
                placeholder="Ex.: RDO_EDITADO"
                onChange={(event) => ledger.setFilters({ eventType: event.target.value })}
              />
            </label>
            <label>
              Tipo de entidade
              <input
                value={ledger.filters.entityType ?? ""}
                placeholder="Ex.: RDO"
                onChange={(event) => ledger.setFilters({ entityType: event.target.value })}
              />
            </label>
            <label>
              ID da entidade
              <input
                value={ledger.filters.entityId ?? ""}
                placeholder="ID exato"
                onChange={(event) => ledger.setFilters({ entityId: event.target.value })}
              />
            </label>
            <label>
              ID do RDO
              <input
                value={ledger.filters.rdoId ?? ""}
                placeholder="ID exato"
                onChange={(event) => ledger.setFilters({ rdoId: event.target.value })}
              />
            </label>
            <label>
              Origem
              <select
                value={ledger.filters.origin ?? ""}
                onChange={(event) => ledger.setFilters({ origin: event.target.value })}
              >
                <option value="">Todas</option>
                <option value="ONLINE">Servidor</option>
                <option value="OFFLINE">Dispositivo</option>
                <option value="SYNC">Sincronização</option>
              </select>
            </label>
            <label>
              Resultado
              <input
                value={ledger.filters.result ?? ""}
                placeholder="Ex.: SYNCED"
                onChange={(event) => ledger.setFilters({ result: event.target.value })}
              />
            </label>
            <label>
              Desde (UTC)
              <input
                type="date"
                value={dateInputValue(ledger.filters.from)}
                onChange={(event) => ledger.setFilters({
                  from: event.target.value ? `${event.target.value}T00:00:00.000Z` : undefined,
                })}
              />
            </label>
            <label>
              Até (UTC)
              <input
                type="date"
                value={dateInputValue(ledger.filters.to)}
                onChange={(event) => ledger.setFilters({
                  to: event.target.value ? `${event.target.value}T23:59:59.999Z` : undefined,
                })}
              />
            </label>
          </fieldset>
          <button type="button" onClick={ledger.clearFilters} disabled={activeFilters === 0}>
            Limpar filtros
          </button>
        </details>
      </section>

      <section className="memory-register" aria-labelledby="memory-results-title">
        <header>
          <div>
            <span>Registro cronológico</span>
            <h3 id="memory-results-title">
              {ledger.totalMatches} {ledger.totalMatches === 1 ? "evento" : "eventos"}
            </h3>
          </div>
          <span>{activeFilters > 0 ? "Recorte filtrado" : "Todo o cache disponível"}</span>
        </header>

        {ledger.error ? (
          <div className="memory-notice memory-notice--error" role="alert">
            <strong>
              {ledger.error.source === "CACHE"
                ? "O cache local não pôde ser lido."
                : "O servidor não confirmou a atualização."}
            </strong>
            <span>
              {ledger.error.message}{" "}
              {ledger.error.source === "CACHE"
                ? "Os registros armazenados podem estar indisponíveis neste dispositivo."
                : "Os registros já armazenados continuam disponíveis."}
            </span>
          </div>
        ) : null}

        {ledger.isLoading ? (
          <div className="memory-empty" role="status">Lendo a Memória deste dispositivo…</div>
        ) : ledger.items.length === 0 ? (
          <div className="memory-empty">
            <strong>
              {activeFilters > 0
                ? "Nenhum evento corresponde aos filtros ativos."
                : "Nenhum evento autorizado foi armazenado ainda."}
            </strong>
            <span>
              {activeFilters > 0
                ? "Ajuste ou limpe o recorte para consultar outros registros."
                : "Conecte-se para carregar o histórico ou registre uma alteração local."}
            </span>
          </div>
        ) : (
          <ol className="memory-list">
            {ledger.items.map((item) => <MemoryRow key={item.key} item={item} />)}
          </ol>
        )}

        {ledger.hasMoreLocal ? (
          <button type="button" className="memory-load-more" onClick={ledger.loadMore}>
            Mostrar mais do cache
          </button>
        ) : null}
      </section>
    </div>
  );
}

function graphCoverageLabel(
  graph: NonNullable<MemoryLedgerViewModel["metadata"]>["graph"],
): string {
  if (!graph) return "Não confirmado";
  const lag = graph.lagEventCount === 0
    ? "em dia"
    : `${graph.lagEventCount} ${graph.lagEventCount === 1 ? "pendente" : "pendentes"}`;
  return `${graph.checkpointCommitSequence} / ${graph.targetCommitSequence} · ${lag}`;
}

function MemoryRow({ item }: { item: MemorySearchDocument }) {
  const title = eventTitle(item.eventType);
  const entity = item.principalName ??
    item.structuralKeys.entityId ??
    "Identidade protegida";
  const worksite = item.worksiteName ?? item.structuralKeys.worksiteId;
  return (
    <li className="memory-entry" data-status={item.syncStatus}>
      <div className="memory-entry__rail" aria-hidden="true">
        <span />
      </div>
      <div className="memory-entry__commit">
        <strong>{item.commitSequence === null ? "Local" : `Commit ${item.commitSequence}`}</strong>
        <time dateTime={item.occurredAt}>{formatDateTime(item.occurredAt)}</time>
      </div>
      <div className="memory-entry__body">
        <div className="memory-entry__title">
          <h4>{title}</h4>
          <span>{memoryStatusLabel(item.syncStatus)}</span>
        </div>
        <p>
          <strong>{entity}</strong>
          {worksite ? ` · ${worksite}` : ""}
          {item.rdoNumber ? ` · RDO ${item.rdoNumber}` : ""}
          {item.serviceName ? ` · ${item.serviceName}` : ""}
        </p>
        <dl className="memory-entry__evidence">
          <div><dt>Evento</dt><dd>{item.eventId}</dd></div>
          <div>
            <dt>Entidade</dt>
            <dd>
              {item.structuralKeys.entityType} · {item.structuralKeys.entityId ?? "Identidade protegida"}
            </dd>
          </div>
          <div><dt>Origem</dt><dd>{item.source ?? item.structuralKeys.origin ?? "Não informada"}</dd></div>
          {item.errorCategory ? (
            <div><dt>Código seguro</dt><dd>{item.errorCategory}</dd></div>
          ) : null}
        </dl>
        {(item.syncStatus === "CONFLICT" || item.syncStatus === "REJECTED") && item.structuralKeys.rdoId ? (
          <a
            className="memory-entry__action"
            href={`/rdos?rdoId=${encodeURIComponent(item.structuralKeys.rdoId)}&eventId=${encodeURIComponent(item.eventId)}`}
          >
            Abrir evidência no RDO
          </a>
        ) : null}
      </div>
    </li>
  );
}

function countFilters(filters: MemoryFilters): number {
  return Object.values(filters).filter((value) =>
    typeof value === "string" ? Boolean(value.trim()) : value !== undefined,
  ).length;
}

function dateInputValue(value: string | undefined): string {
  return value?.slice(0, 10) ?? "";
}

function eventTitle(eventType: string): string {
  const known = operationalEventLabel(eventType);
  return known === "Atividade registrada"
    ? eventType.toLocaleLowerCase("pt-BR").replaceAll("_", " ")
      .replace(/^./, (letter) => letter.toLocaleUpperCase("pt-BR"))
    : known;
}

function formatDateTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat("pt-BR", {
      dateStyle: "short",
      timeStyle: "short",
      timeZone: "UTC",
    }).format(date);
}
