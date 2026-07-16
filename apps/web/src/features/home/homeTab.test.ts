import { describe, expect, it } from "vitest";
import {
  homeTabFromSearch,
  searchForHomeTab,
} from "./homeTab";

describe("homeTab", () => {
  it("defaults invalid values to overview", () => {
    expect(homeTabFromSearch(new URLSearchParams("tab=unknown")))
      .toBe("overview");
  });

  it("recognizes the memory tab", () => {
    expect(homeTabFromSearch(new URLSearchParams("tab=memory")))
      .toBe("memory");
  });

  it("preserves memory filters when selecting memory", () => {
    expect(searchForHomeTab(
      new URLSearchParams("obraId=o1"),
      "memory",
    ).toString()).toBe("obraId=o1&tab=memory");
  });
});
