import { useEffect } from "react";
import { useSearchParams } from "react-router-dom";

import { CortexShell } from "../../components/shell/CortexShell";
import { useStaviaLauncher } from "../stavia/useStaviaLauncher";
import { HomeOverview } from "./HomeOverview";
import { HomeSubnav } from "./HomeSubnav";
import { homeTabFromSearch } from "./homeTab";
import { MemoryLedger } from "./memory/MemoryLedger";
import { useHomeData } from "./useHomeData";

export function HomePage() {
  const data = useHomeData();
  const [search] = useSearchParams();
  const activeTab = homeTabFromSearch(search);
  const { setStaviaContext } = useStaviaLauncher();

  useEffect(() => {
    setStaviaContext({ obraId: data.focusedObra?.id ?? "" });
  }, [data.focusedObra?.id, setStaviaContext]);

  return (
    <CortexShell
      active="home"
      onRefresh={data.reload}
      isRefreshing={data.isLoading}
    >
      <main className="home-dashboard">
        <header className="home-page-heading">
          <div>
            <span>Córtex 2.1 / Centro operacional</span>
            <h1>Visão do empreendimento</h1>
          </div>
          <p>
            Leitura consolidada da operação e de sua memória ontológica,
            preservada por obra, entidade e responsável.
          </p>
        </header>
        <HomeSubnav />
        <section
          id={`home-panel-${activeTab}`}
          role="tabpanel"
          aria-labelledby={`home-tab-${activeTab}`}
        >
          {activeTab === "memory" ? (
            <MemoryLedger obras={data.obras} />
          ) : (
            <HomeOverview data={data} />
          )}
        </section>
      </main>
    </CortexShell>
  );
}
