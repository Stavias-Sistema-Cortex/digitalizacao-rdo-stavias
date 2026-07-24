export type HomeTab = "overview" | "memory";

const HOME_TABS: readonly HomeTab[] = ["overview", "memory"];

interface MoveHomeTabFocusOptions {
  current: HomeTab;
  key: string;
  focus: (tab: HomeTab) => void;
  select: (tab: HomeTab) => void;
}

export function moveHomeTabFocus({
  current,
  key,
  focus,
  select,
}: MoveHomeTabFocusOptions): boolean {
  const currentIndex = HOME_TABS.indexOf(current);
  let next: HomeTab;
  switch (key) {
    case "ArrowRight":
      next = HOME_TABS[(currentIndex + 1) % HOME_TABS.length];
      break;
    case "ArrowLeft":
      next = HOME_TABS[(currentIndex - 1 + HOME_TABS.length) % HOME_TABS.length];
      break;
    case "Home":
      next = HOME_TABS[0];
      break;
    case "End":
      next = HOME_TABS[HOME_TABS.length - 1];
      break;
    default:
      return false;
  }
  select(next);
  focus(next);
  return true;
}

export function homeTabFromSearch(search: URLSearchParams): HomeTab {
  return search.get("tab") === "memory" ? "memory" : "overview";
}

export function searchForHomeTab(
  current: URLSearchParams,
  tab: HomeTab,
): URLSearchParams {
  const next = new URLSearchParams(current);
  next.set("tab", tab);
  return next;
}
