import { useSearchParams } from "react-router-dom";

import { CortexShell } from "../../components/shell/CortexShell";
import { HomeOverview } from "./HomeOverview";
import { HomeSubnav } from "./HomeSubnav";
import { homeTabFromSearch } from "./homeTab";
import { MaisStaviasCard } from "./MaisStaviasCard";
import { MemoryLedger } from "./memory/MemoryLedger";
import { useHomeData } from "./useHomeData";

export function HomePage() {
  const data = useHomeData();
  const [search] = useSearchParams();
  const activeTab = homeTabFromSearch(search);
  const moreCard = (
    <MaisStaviasCard />
  );

  return (
    <CortexShell
      active="home"
      onRefresh={data.reload}
      isRefreshing={data.isLoading}
    >
      <main className="home-dashboard">
        <header className="home-page-heading">
          <div>
            <span>Córtex operacional</span>
            <h1>Visão do empreendimento</h1>
          </div>
          <p>Operação atual e registro rastreável no mesmo espaço de trabalho.</p>
        </header>
        <HomeSubnav />
        <section
          id={`home-panel-${activeTab}`}
          role="tabpanel"
          aria-labelledby={`home-tab-${activeTab}`}
        >
          {activeTab === "memory"
            ? <MemoryLedger obras={data.obras} />
            : (
              <HomeOverview
                data={data}
                moreCard={moreCard}
              />
            )}
        </section>
      </main>
    </CortexShell>
  );
}
