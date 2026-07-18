import { useEffect } from "react";
import { useSearchParams } from "react-router-dom";

import { InstitutionalPageHeader } from "../../components/institutional/InstitutionalPageHeader";
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
        <InstitutionalPageHeader
          className="home-command-header"
          eyebrow="Centro operacional"
          title="Visão do empreendimento"
          description="Comando operacional de infraestrutura com exceções, recorte de obra e rastreabilidade local explícitos."
        />
        <HomeSubnav />
        <section
          id={`home-panel-${activeTab}`}
          role="tabpanel"
          aria-labelledby={`home-tab-${activeTab}`}
        >
          {activeTab === "memory" ? (
            <MemoryLedger
              key={search.get("event") ?? "memory-ledger"}
              obras={data.obras}
            />
          ) : (
            <HomeOverview data={data} />
          )}
        </section>
      </main>
    </CortexShell>
  );
}
