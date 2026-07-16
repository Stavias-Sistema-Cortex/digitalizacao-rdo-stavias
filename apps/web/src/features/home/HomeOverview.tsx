import { useMemo, useState } from "react";

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
  } = data;
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

  return (
    <div className="home-overview">
      <header className="home-topbar">
        <div>
          <span className="home-section-index">01 / Operação</span>
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
        <section className="home-obra-card home-obra-card--empty">
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

      <div className="home-cards-grid">
        {focusedObra ? <FinanceHomeCard obraId={focusedObra.id} /> : null}
        <MensagensCard />
        <MemorySummaryCard
          events={events}
          obraId={focusedObra?.id ?? null}
        />
        <TimeCard latestRdo={latestRdo} />
        <MaisStaviasCard />
      </div>
    </div>
  );
}
