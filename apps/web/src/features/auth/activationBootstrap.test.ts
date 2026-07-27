import { beforeEach, describe, expect, it, vi } from "vitest";

import { probeActivationOnly } from "./activationBootstrap";

const localValues = new Map<string, string>();

vi.stubGlobal("localStorage", {
  getItem: (key: string) => localValues.get(key) ?? null,
  removeItem: (key: string) => localValues.delete(key),
  setItem: (key: string, value: string) => localValues.set(key, value),
});

describe("activation bootstrap", () => {
  beforeEach(() => {
    localValues.clear();
  });

  it("does not probe the cookie-backed session while remote isolation is persisted", async () => {
    const fetchImplementation = vi.fn();
    localStorage.setItem("cortex.auth.remote-session-isolation", "1");

    await expect(probeActivationOnly(fetchImplementation)).resolves.toBe(false);

    expect(fetchImplementation).not.toHaveBeenCalled();
  });
});
