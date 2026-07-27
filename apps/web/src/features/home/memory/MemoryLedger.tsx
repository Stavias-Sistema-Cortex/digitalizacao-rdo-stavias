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
  const reviewItems = ledger.items.filter((item) => item.review !== null);
  return (
    <div className="memory-ledger">
      <header className="memory-ledger__header">
        <div>
          <h2>Memória operacional</h2>
        </div>
        <div className="memory-ledger__actions">
          <button
            type="button"
            className="memory-ledger__export"
            onClick={ledger.exportLedger}
            disabled={ledger.isExporting}
          >
            {ledger.isExporting ? "Exportando…" : "Exportar"}
          </button>
          <button
            type="button"
            className="memory-ledger__refresh"
            onClick={ledger.refresh}
            disabled={ledger.isRefreshing}
          >
            {ledger.isRefreshing ? "Sincronizando…" : "Atualizar"}
          </button>
        </div>
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
        <div
          className="memory-query__view"
          role="group"
          aria-label="Visão da Memória por dispositivo"
        >
          <button
            type="button"
            aria-pressed={ledger.viewMode !== "THIS_DEVICE"}
            onClick={() => ledger.setViewMode("CONSOLIDATED")}
          >
            Consolidado
          </button>
          <button
            type="button"
            aria-pressed={ledger.viewMode === "THIS_DEVICE"}
            disabled={!ledger.currentDeviceId}
            onClick={() => ledger.setViewMode("THIS_DEVICE")}
          >
            Este dispositivo
          </button>
          {!ledger.currentDeviceId ? (
            <span>Dispositivo local ainda não registrado.</span>
          ) : null}
        </div>
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
              ID interno do ator
              <input
                value={ledger.filters.actorId ?? ""}
                placeholder="ID exato no escopo autorizado"
                onChange={(event) => ledger.setFilters({
                  actorId: event.target.value,
                })}
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

      {ledger.exportNotice ? (
        <div className="memory-notice" role="status">
          <span>{ledger.exportNotice}</span>
        </div>
      ) : null}

      {reviewItems.length > 0 ? (
        <section
          className="memory-review"
          aria-labelledby="memory-review-title"
        >
          <header>
            <div>
              <span>Evidência terminal preservada</span>
              <h3 id="memory-review-title">Revisão necessária</h3>
            </div>
            <span>{reviewItems.length} no recorte visível</span>
          </header>
          {ledger.reviewNotice ? (
            <div className="memory-notice" role="status">
              <span>{ledger.reviewNotice}</span>
            </div>
          ) : null}
          <ul>
            {reviewItems.map((item) => (
              <MemoryReviewCard key={`review:${item.key}`} item={item} ledger={ledger} />
            ))}
          </ul>
        </section>
      ) : null}

      <section className="memory-register" aria-labelledby="memory-results-title">
        <header>
          <div>
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

function MemoryReviewCard({
  item,
  ledger,
}: {
  item: MemorySearchDocument;
  ledger: MemoryLedgerViewModel;
}) {
  const review = item.review;
  if (!review) return null;
  const reconciling = review.clientMutationId !== null &&
    ledger.reconcilingMutationId === review.clientMutationId;
  return (
    <li className="memory-review__item" data-status={review.status}>
      <div>
        <strong>{eventTitle(item.eventType)}</strong>
        <span>{memoryStatusLabel(item.syncStatus)}</span>
      </div>
      <dl>
        <div><dt>Versão-base</dt><dd>{versionLabel(review.baseVersion)}</dd></div>
        <div><dt>Versão registrada</dt><dd>{versionLabel(review.eventVersion)}</dd></div>
        <div><dt>Versão remota</dt><dd>{versionLabel(review.remoteVersion)}</dd></div>
        <div>
          <dt>Estado local</dt>
          <dd>{review.localStateAvailable ? "Disponível" : "Indisponível"}</dd>
        </div>
        <div>
          <dt>Estado remoto</dt>
          <dd>
            {review.remoteStateAvailable
              ? "Disponível"
              : "Estado remoto indisponível"}
          </dd>
        </div>
      </dl>
      {review.changedFields.length > 0 ? (
        <p>Campos alterados: {review.changedFields.join(", ")}.</p>
      ) : null}
      {review.conflictFields.length > 0 ? (
        <p>Divergências: {review.conflictFields.join(", ")}.</p>
      ) : null}
      {review.unavailableReason ? (
        <p>{reviewReason(review.unavailableReason)}</p>
      ) : null}
      {review.canReconcile && review.clientMutationId ? (
        <button
          type="button"
          disabled={reconciling}
          onClick={() => ledger.reconcileReview(item)}
        >
          {reconciling ? "Conciliando…" : "Conciliar alterações"}
        </button>
      ) : null}
    </li>
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
  const responsibleUser = item.responsibleUserName?.trim() ||
    item.structuralKeys.actorId ||
    "Sistema";
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
          <div className="memory-entry__actor">
            <dt>Responsável</dt>
            <dd>{responsibleUser}</dd>
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

function versionLabel(version: number | null): string {
  return version === null ? "Não informada" : String(version);
}

function reviewReason(
  reason: NonNullable<MemorySearchDocument["review"]>["unavailableReason"],
): string {
  const reasons: Record<NonNullable<typeof reason>, string> = {
    REJECTED: "A alteração foi rejeitada; a evidência permanece somente para revisão.",
    LOCAL_EVIDENCE_UNAVAILABLE:
      "A evidência canônica local necessária não está disponível.",
    REMOTE_SNAPSHOT_UNAVAILABLE:
      "O servidor informou o conflito, mas não forneceu um snapshot remoto completo.",
    UNSUPPORTED_ENTITY:
      "Este tipo de entidade não possui conciliação canônica segura.",
    CREATE_CONFLICT_REQUIRES_REVIEW:
      "Conflitos de criação exigem revisão manual.",
    REMOTE_SNAPSHOT_MISMATCH:
      "O snapshot remoto não corresponde à entidade autorizada.",
    FIELD_CONFLICT:
      "Os mesmos campos foram alterados local e remotamente.",
  };
  return reason ? reasons[reason] : "";
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
    }).format(date);
}
