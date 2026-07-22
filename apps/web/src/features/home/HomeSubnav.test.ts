import { describe, expect, it } from "vitest";

import { homeTabFromSearch, searchForHomeTab } from "./homeTab";

describe("Home subnavigation", () => {
  it("keeps overview as the safe default and selects Memory explicitly", () => {
    expect(homeTabFromSearch(new URLSearchParams())).toBe("overview");
    expect(homeTabFromSearch(new URLSearchParams("tab=memory"))).toBe("memory");
    expect(homeTabFromSearch(new URLSearchParams("tab=unknown"))).toBe("overview");
  });

  it("preserves unrelated parameters while switching panels", () => {
    const next = searchForHomeTab(
      new URLSearchParams("obraId=obra-1&tab=overview"),
      "memory",
    );
    expect(next.toString()).toBe("obraId=obra-1&tab=memory");
  });
});
