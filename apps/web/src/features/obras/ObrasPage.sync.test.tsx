// @vitest-environment jsdom

import type { PropsWithChildren } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const homeData = vi.hoisted(() => ({
  obras: [],
  focusedObra: null,
  focusedObraId: null,
  setFocusedObraId: vi.fn(),
  events: [],
  isLoading: false,
  hasConfirmedRemoteHydration: false,
  reload: vi.fn(),
}));

vi.mock("../../components/shell/CortexShell", () => ({
  CortexShell: ({ children }: PropsWithChildren) => <>{children}</>,
}));

vi.mock("../home/useHomeData", () => ({
  useHomeData: () => homeData,
}));

vi.mock("../auth/authSession", () => ({
  getSession: () => null,
  isAlfa: () => false,
}));

import { ObrasPage } from "./ObrasPage";

beforeEach(() => {
  Object.defineProperty(navigator, "onLine", {
    configurable: true,
    value: true,
  });
  homeData.hasConfirmedRemoteHydration = false;
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("ObrasPage sync truth", () => {
  it("does not call an online cache synced before remote hydration is confirmed", () => {
    const { rerender } = render(<ObrasPage />);

    expect(screen.getByRole("status")).toHaveAttribute(
      "data-status",
      "LOCAL",
    );

    homeData.hasConfirmedRemoteHydration = true;
    rerender(<ObrasPage />);

    expect(screen.getByRole("status")).toHaveAttribute(
      "data-status",
      "SYNCED",
    );
  });
});
