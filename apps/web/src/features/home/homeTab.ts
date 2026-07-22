export type HomeTab = "overview" | "memory";

export function homeTabFromSearch(
  search: URLSearchParams,
): HomeTab {
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
