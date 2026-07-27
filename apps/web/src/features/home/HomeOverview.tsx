import { useMemo, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";

import { SyncStateStrip } from "../../components/institutional/SyncStateStrip";
import { useSyncStatus } from "../../lib/sync/useSyncStatus";
import {
  filterObrasByChip,
  filterObrasByRodovia,
  filterObrasByUf,
  OBRA_STATUS_CHIPS,
  type ObraStatusChip,
} from "./homeFilters";
import { FinanceHomeCard } from "./FinanceHomeCard";
import { MensagensCard } from "./MensagensCard";
import { ObraFocusCard } from "./ObraFocusCard";
import { TimeCard } from "./TimeCard";
import type { HomeData } from "./useHomeData";

export function HomeOverview({
  data,
  moreCard,
}: {
  data: HomeData;
  moreCard: ReactNode;
}) {
  const {
    obras,
    focusedObra,
    setFocusedObraId,
    snapshots,
    events,
    latestRdo,
    isLoading,
    dataUpdatedAt,
  } = data;
  const { snapshot } = useSyncStatus();
  const [chip, setChip] = useState<ObraStatusChip>("TODAS");
  const [ufFilter, setUfFilter] = useState("");
  const [rodoviaFilter, setRodoviaFilter] = useState("");

  const ufs = useMemo(
    () => [...new Set(obras.map((obra) => obra.uf))]
      .filter((value): value is string => Boolean(value)),
    [obras],
  );
  const rodovias = useMemo(
    () => [...new Set(obras.map((obra) => obra.rodovia))]
      .filter((value): value is string => Boolean(value)),
    [obras],
  );
  const obraOptions = useMemo(
    () => filterObrasByRodovia(
      filterObrasByUf(filterObrasByChip(obras, chip), ufFilter),
      rodoviaFilter,
    ),
    [obras, chip, rodoviaFilter, ufFilter],
  );
  const selectorOptions = useMemo(() => {
    if (!focusedObra || obraOptions.some((obra) => obra.id === focusedObra.id)) {
      return obraOptions;
    }
    return [focusedObra, ...obraOptions];
  }, [focusedObra, obraOptions]);
  const deviceQueueCount =
    snapshot.pendingCount + snapshot.syncingCount;
  const focusedWorksiteQueueCount = events.filter(
    (event) => event.syncStatus !== "SYNCED",
  ).length;
  const localUpdateLabel = useMemo(() => {
    if (!dataUpdatedAt) {
      return "Não registrada";
    }

    const date = new Date(dataUpdatedAt);
    if (Number.isNaN(date.getTime())) {
      return "Registro local disponível";
    }

    return new Intl.DateTimeFormat("pt-BR", {
      dateStyle: "short",
      timeStyle: "short",
    }).format(date);
  }, [dataUpdatedAt]);

  return (
    <div className="home-overview">
      <header className="home-topbar">
        <h2>Obras relacionadas</h2>
        <div className="home-chips" role="group" aria-label="Filtrar obras por status">
          {OBRA_STATUS_CHIPS.map((option) => (
            <button
              key={option.value}
              type="button"
              className={option.value === chip ? "chip chip--active" : "chip"}
              onClick={() => setChip(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
        <div className="home-uf-filter">
          <span>Filtrar por:</span>
          <select
            value={ufFilter}
            aria-label="Filtrar por UF"
            onChange={(event) => setUfFilter(event.target.value)}
          >
            <option value="">UF: todas</option>
            {ufs.map((uf) => <option key={uf} value={uf}>{uf}</option>)}
          </select>
          <select
            value={rodoviaFilter}
            aria-label="Filtrar por rodovia"
            onChange={(event) => setRodoviaFilter(event.target.value)}
          >
            <option value="">Rodovia: todas</option>
            {rodovias.map((rodovia) => (
              <option key={rodovia} value={rodovia}>{rodovia}</option>
            ))}
          </select>
        </div>
      </header>

      <section
        aria-label="Exceções de sincronização"
        className="home-exception-register"
      >
        <header className="home-exception-register__heading">
          <div>
            <h2>Sincronização</h2>
          </div>
          <p>
            Atualização local
            {dataUpdatedAt ? (
              <>
                {" · "}
                <time dateTime={dataUpdatedAt}>{localUpdateLabel}</time>
              </>
            ) : (
              ` · ${localUpdateLabel}`
            )}
          </p>
        </header>

        <SyncStateStrip
          snapshot={snapshot}
          className="home-exception-register__sync"
        />

        <dl className="home-exception-register__facts">
          <div>
            <dt>Na fila</dt>
            <dd>{snapshot.isLoading ? "—" : deviceQueueCount}</dd>
          </div>
          <div>
            <dt>Conflitos</dt>
            <dd>{snapshot.isLoading ? "—" : snapshot.conflictCount}</dd>
          </div>
          <div>
            <dt>Falhas</dt>
            <dd>{snapshot.isLoading ? "—" : snapshot.errorCount}</dd>
          </div>
          <div>
            <dt>Revisões</dt>
            <dd>{snapshot.isLoading ? "—" : snapshot.reviewCount}</dd>
          </div>
          <div>
            <dt>Aguardando</dt>
            <dd>{focusedWorksiteQueueCount}</dd>
          </div>
        </dl>

        <Link to="/home?tab=memory">
          Ver memória
        </Link>
      </section>

      {focusedObra ? (
        <ObraFocusCard
          obra={focusedObra}
          obraOptions={selectorOptions}
          onSelectObra={setFocusedObraId}
          snapshots={snapshots}
          events={events}
          latestRdo={latestRdo}
        />
      ) : (
        <section className="home-obra-card home-obra-card--empty">
          {isLoading ? (
            <p>Carregando obras…</p>
          ) : obras.length === 0 ? (
            <p>Nenhuma obra disponível. Conecte-se uma vez para carregar seu escopo.</p>
          ) : (
            <p>Escolha uma obra para começar.</p>
          )}
        </section>
      )}

      <div className="home-cards-grid">
        {focusedObra ? <FinanceHomeCard obraId={focusedObra.id} /> : null}
        <MensagensCard />
        <article className="home-card home-memory-card">
          <h3>Memória operacional</h3>
          <p>
            {events.length > 0
              ? `${events.length} eventos locais vinculados à obra selecionada.`
              : "Nenhum evento local vinculado à obra selecionada."}
          </p>
          <Link to="/home?tab=memory">Abrir registro completo</Link>
        </article>
        <TimeCard latestRdo={latestRdo} />
        {moreCard}
      </div>
    </div>
  );
}
