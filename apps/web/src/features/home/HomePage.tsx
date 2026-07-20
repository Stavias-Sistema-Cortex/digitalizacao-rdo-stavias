import { useEffect, useMemo, useState } from "react";

import { CortexShell } from "../../components/shell/CortexShell";
import { useStaviaLauncher } from "../stavia/useStaviaLauncher";
import {
  filterObrasByChip,
  filterObrasByRodovia,
  filterObrasByUf,
  OBRA_STATUS_CHIPS,
  type ObraStatusChip,
} from "./homeFilters";
import { ObraFocusCard } from "./ObraFocusCard";
import { AtualizacoesCard } from "./AtualizacoesCard";
import { MaisStaviasCard } from "./MaisStaviasCard";
import { MensagensCard } from "./MensagensCard";
import { TimeCard } from "./TimeCard";
import { FinanceHomeCard } from "./FinanceHomeCard";
import { useHomeData } from "./useHomeData";

export function HomePage() {
  const {
    obras,
    focusedObra,
    setFocusedObraId,
    snapshots,
    events,
    latestRdo,
    isLoading,
    reload,
  } = useHomeData();

  const [chip, setChip] =
    useState<ObraStatusChip>("TODAS");
  const [ufFilter, setUfFilter] = useState("");
  const [rodoviaFilter, setRodoviaFilter] = useState("");
  const { setStaviaContext } = useStaviaLauncher();

  useEffect(() => {
    setStaviaContext({ obraId: focusedObra?.id ?? "" });
  }, [focusedObra?.id, setStaviaContext]);

  const ufs = useMemo(
    () =>
      [...new Set(obras.map((obra) => obra.uf))].filter(
        (uf): uf is string => Boolean(uf),
      ),
    [obras],
  );

  const rodovias = useMemo(
    () =>
      [
        ...new Set(obras.map((obra) => obra.rodovia)),
      ].filter((rodovia): rodovia is string =>
        Boolean(rodovia),
      ),
    [obras],
  );

  const obraOptions = useMemo(
    () =>
      filterObrasByRodovia(
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
    return obraOptions.some(
      (option) => option.id === focusedObra.id,
    )
      ? obraOptions
      : [focusedObra, ...obraOptions];
  }, [obraOptions, focusedObra]);

  return (
    <CortexShell
      active="home"
      onRefresh={reload}
      isRefreshing={isLoading}
    >
      <main className="home-dashboard">
        <header className="home-topbar">
          <h1>Obras Relacionadas</h1>
          <div
            className="home-chips"
            role="group"
            aria-label="Filtrar obras por status"
          >
            {OBRA_STATUS_CHIPS.map((option) => (
              <button
                key={option.value}
                type="button"
                className={
                  option.value === chip
                    ? "chip chip--active"
                    : "chip"
                }
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
              onChange={(event) => {
                setUfFilter(event.target.value);
              }}
            >
              <option value="">UF: todas</option>
              {ufs.map((uf) => (
                <option key={uf} value={uf}>
                  {uf}
                </option>
              ))}
            </select>
            <select
              value={rodoviaFilter}
              aria-label="Filtrar por rodovia"
              onChange={(event) => {
                setRodoviaFilter(event.target.value);
              }}
            >
              <option value="">Rodovia: todas</option>
              {rodovias.map((rodovia) => (
                <option key={rodovia} value={rodovia}>
                  {rodovia}
                </option>
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
                Nenhuma obra disponível ainda. Conecte-se
                uma vez para carregar suas obras
                relacionadas.
              </p>
            ) : (
              <p>Escolha uma obra para começar.</p>
            )}
          </section>
        )}

        <div className="home-cards-grid">
          {focusedObra ? <FinanceHomeCard obraId={focusedObra.id} /> : null}
          <MensagensCard />
          <AtualizacoesCard events={events} />
          <TimeCard latestRdo={latestRdo} />
          <MaisStaviasCard />
        </div>
      </main>
    </CortexShell>
  );
}
