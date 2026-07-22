import { useRef } from "react";
import { useSearchParams } from "react-router-dom";

import {
  homeTabFromSearch,
  moveHomeTabFocus,
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
  const tabs = useRef<Record<HomeTab, HTMLButtonElement | null>>({
    overview: null,
    memory: null,
  });

  const selectTab = (tab: HomeTab) => {
    setSearch(searchForHomeTab(search, tab));
  };

  return (
    <nav className="home-subnav" aria-label="Seções da Home">
      <div role="tablist" aria-label="Conteúdo da Home">
        {TABS.map((tab) => (
          <button
            ref={(element) => {
              tabs.current[tab.id] = element;
            }}
            key={tab.id}
            id={`home-tab-${tab.id}`}
            type="button"
            role="tab"
            aria-selected={activeTab === tab.id}
            aria-controls={`home-panel-${tab.id}`}
            tabIndex={activeTab === tab.id ? 0 : -1}
            onClick={() => selectTab(tab.id)}
            onKeyDown={(event) => {
              const handled = moveHomeTabFocus({
                current: tab.id,
                key: event.key,
                select: selectTab,
                focus: (target) => tabs.current[target]?.focus(),
              });
              if (handled) event.preventDefault();
            }}
          >
            {tab.label}
          </button>
        ))}
      </div>
    </nav>
  );
}
