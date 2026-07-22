import { useSearchParams } from "react-router-dom";

import {
  homeTabFromSearch,
  searchForHomeTab,
  type HomeTab,
} from "./homeTab";

const TABS: readonly { id: HomeTab; label: string }[] = [
  { id: "overview", label: "Visão geral" },
  { id: "memory", label: "Memória" },
];

export function HomeSubnav() {
  const [search, setSearch] = useSearchParams();
  const activeTab = homeTabFromSearch(search);

  return (
    <nav className="home-subnav" aria-label="Seções da Home">
      <div role="tablist" aria-label="Conteúdo da Home">
        {TABS.map((tab) => (
          <button
            key={tab.id}
            id={`home-tab-${tab.id}`}
            type="button"
            role="tab"
            aria-selected={activeTab === tab.id}
            aria-controls={`home-panel-${tab.id}`}
            tabIndex={activeTab === tab.id ? 0 : -1}
            onClick={() => setSearch(searchForHomeTab(search, tab.id))}
          >
            {tab.label}
          </button>
        ))}
      </div>
    </nav>
  );
}
