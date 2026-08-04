import { useSearchParams } from "react-router";

import { CortexShell } from "../../components/shell/CortexShell";
import { OperationalWorkspace } from "../../components/workspace/OperationalWorkspace";
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
      <OperationalWorkspace
        className="home-dashboard"
        eyebrow="Córtex operacional"
        title="Visão do empreendimento"
        status={data.isLoading
          ? { code: "SYNCING", label: "Atualizando o banco local" }
          : data.dataUpdatedAt
            ? {
                code: "LOCAL",
                label: "Dados disponíveis neste dispositivo",
                detail: `Última atualização registrada em ${data.dataUpdatedAt}`,
              }
            : { code: "LOCAL", label: "Nenhum dado local disponível" }}
      >
        <HomeSubnav />
        <section
          id={`home-panel-${activeTab}`}
          role="tabpanel"
          aria-labelledby={`home-tab-${activeTab}`}
        >
          {activeTab === "memory"
            ? (
              <MemoryLedger
                obras={data.obras}
                somenteRevisaoInicial={search.get("pendencias") === "1"}
              />
            )
            : (
              <HomeOverview
                data={data}
                moreCard={moreCard}
              />
            )}
        </section>
      </OperationalWorkspace>
    </CortexShell>
  );
}
