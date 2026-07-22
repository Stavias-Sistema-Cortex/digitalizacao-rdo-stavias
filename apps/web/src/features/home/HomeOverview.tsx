import { useMemo, useState } from "react";
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
import { MaisStaviasCard } from "./MaisStaviasCard";
import { MemorySummaryCard } from "./MemorySummaryCard";
import { MensagensCard } from "./MensagensCard";
import { ObraFocusCard } from "./ObraFocusCard";
import { TimeCard } from "./TimeCard";
import type { HomeData } from "./useHomeData";

interface HomeOverviewProps {
  data: HomeData;
}

export function HomeOverview({ data }: HomeOverviewProps) {
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
      .filter((uf): uf is string => Boolean(uf)),
    [obras],
  );
  const rodovias = useMemo(
    () => [...new Set(obras.map((obra) => obra.rodovia))]
      .filter((rodovia): rodovia is string => Boolean(rodovia)),
    [obras],
  );
  const obraOptions = useMemo(
    () => filterObrasByRodovia(
      filterObrasByUf(
        filterObrasByChip(obras, chip),
        ufFilter,
      ),
      rodoviaFilter,
    ),
    [obras, chip, ufFilter, rodoviaFilter],
  );
  const selectorOptions = useMemo(() => {
    if (!focusedObra) {
      return obraOptions;
    }
    return obraOptions.some((option) => option.id === focusedObra.id)
      ? obraOptions
      : [focusedObra, ...obraOptions];
  }, [obraOptions, focusedObra]);
  const exceptionEvents = useMemo(
    () => events.filter((event) => event.syncStatus !== "SYNCED"),
    [events],
  );
  const memorySearch = useMemo(() => {
    const params = new URLSearchParams({ tab: "memory" });
    if (focusedObra?.id) {
      params.set("obraId", focusedObra.id);
    }
    return params.toString();
  }, [focusedObra]);
  const pendingCount = snapshot.pendingCount + snapshot.syncingCount;
  const freshness = formatFreshness(dataUpdatedAt);
  const exceptionSummary = exceptionEvents.length === 1
    ? "alteração local aguarda"
    : "alterações locais aguardam";

  return (
    <div className="home-overview">
      <section
        className="home-exception-register"
        aria-labelledby="home-exceptions-heading"
      >
        <header className="home-exception-register__heading">
          <div>
            <span className="home-section-index">Leitura prioritária</span>
            <h2 id="home-exceptions-heading">Exceções operacionais</h2>
            <p>
              Pendências, revisões, conflitos e atualização local antecedem a
              leitura consolidada do empreendimento. Os totais de
              sincronização são deste dispositivo. Os detalhes de cada
              alteração permanecem concentrados na Memória.
            </p>
          </div>
          <p className="home-exception-register__sync-caption">
            Última sincronização, fila local e conflitos permanecem explícitos.
          </p>
        </header>

        <dl className="home-exception-register__facts">
          <div>
            <dt>Conflitos no dispositivo</dt>
            <dd>{snapshot.isLoading ? "—" : snapshot.conflictCount}</dd>
          </div>
          <div>
            <dt>Falhas no dispositivo</dt>
            <dd>{snapshot.isLoading ? "—" : snapshot.errorCount}</dd>
          </div>
          <div>
            <dt>Revisões necessárias</dt>
            <dd>{snapshot.isLoading ? "—" : snapshot.reviewCount}</dd>
          </div>
          <div>
            <dt>Na fila do dispositivo</dt>
            <dd>{snapshot.isLoading ? "—" : pendingCount}</dd>
          </div>
          <div>
            <dt>Atualização local</dt>
            <dd>
              {dataUpdatedAt && freshness ? (
                <time dateTime={dataUpdatedAt}>{freshness}</time>
              ) : (
                "Não registrada"
              )}
            </dd>
          </div>
        </dl>

        <SyncStateStrip
          className="home-exception-register__sync"
          snapshot={snapshot}
        />

        {exceptionEvents.length === 0 ? (
          <p className="home-exception-register__empty">
            Nenhum evento local fora do estado sincronizado nesta obra.
          </p>
        ) : (
          <p className="home-exception-register__empty">
            {exceptionEvents.length} {exceptionSummary} tratamento ou confirmação.
            {" "}
            <Link to={`/home?${memorySearch}`}>Consultar Memória</Link>
          </p>
        )}
      </section>

      <header className="home-topbar">
        <div>
          <span className="home-section-index">Recorte da operação</span>
          <h2>Obras relacionadas</h2>
        </div>
        <div
          className="home-chips"
          role="group"
          aria-label="Filtrar obras por status"
        >
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
          <span>Escopo</span>
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
        <section className="home-obra-card home-worksite-command home-obra-card--empty">
          {isLoading ? (
            <p>Carregando obras…</p>
          ) : obras.length === 0 ? (
            <p>
              Nenhuma obra disponível. Conecte-se uma vez para carregar o
              escopo operacional autorizado.
            </p>
          ) : (
            <p>Escolha uma obra para iniciar a leitura operacional.</p>
          )}
        </section>
      )}

      <section
        className="home-command-summaries"
        aria-label="Resumos operacionais"
      >
        {focusedObra ? <FinanceHomeCard obraId={focusedObra.id} /> : null}
        <MemorySummaryCard
          events={events}
          obraId={focusedObra?.id ?? null}
        />
        <TimeCard latestRdo={latestRdo} />
      </section>

      <aside className="home-secondary-links" aria-label="Acessos secundários">
        <MensagensCard />
        <MaisStaviasCard />
      </aside>
    </div>
  );
}

function formatFreshness(value: string | null): string | null {
  if (!value) {
    return null;
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}
