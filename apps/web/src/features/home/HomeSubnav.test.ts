import { describe, expect, it, vi } from "vitest";

import {
  homeTabFromSearch,
  moveHomeTabFocus,
  searchForHomeTab,
} from "./homeTab";

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

  it.each([
    ["overview", "ArrowRight", "memory"],
    ["memory", "ArrowRight", "overview"],
    ["overview", "ArrowLeft", "memory"],
    ["memory", "ArrowLeft", "overview"],
    ["memory", "Home", "overview"],
    ["overview", "End", "memory"],
  ] as const)(
    "moves roving focus from %s with %s to %s",
    (current, key, expected) => {
      const focused: string[] = [];
      const selected: string[] = [];
      const handled = moveHomeTabFocus({
        current,
        key,
        focus: (tab) => focused.push(tab),
        select: (tab) => selected.push(tab),
      });

      expect(handled).toBe(true);
      expect(focused).toEqual([expected]);
      expect(selected).toEqual([expected]);
    },
  );

  it("leaves unrelated keys to the browser", () => {
    expect(moveHomeTabFocus({
      current: "overview",
      key: "Tab",
      focus: vi.fn(),
      select: vi.fn(),
    })).toBe(false);
  });
});
