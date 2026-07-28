// @vitest-environment jsdom

import type { PropsWithChildren } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const homeData = vi.hoisted(() => ({
  obras: [{
    id: "obra-1",
    nome: "Obra teste",
    status: "ATIVA",
    codigoContrato: "CTR-1",
    cliente: null,
    cidade: null,
    uf: null,
    rodovia: null,
    latitude: null,
    longitude: null,
    valorContratual: null,
    updatedAt: "2026-07-27T14:00:00.000Z",
  }],
  focusedObra: {
    id: "obra-1",
    nome: "Obra teste",
    status: "ATIVA",
    codigoContrato: "CTR-1",
    cliente: null,
    cidade: null,
    uf: null,
    rodovia: null,
    latitude: null,
    longitude: null,
    valorContratual: null,
    updatedAt: "2026-07-27T14:00:00.000Z",
  },
  focusedObraId: "obra-1",
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
  AUTH_SESSION_CHANGED_EVENT: "cortex-auth-session-changed",
  getSession: () => null,
  isAlfa: () => false,
}));

vi.mock("./obraLifecycle", () => ({
  queueUpdateObra: vi.fn(),
  queueDeactivateObra: vi.fn(),
  queueArchiveObra: vi.fn(),
  queueRestoreObra: vi.fn(),
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

  it("mantém a ontologia recolhida até a pessoa abrir a lista", () => {
    render(<ObrasPage />);

    const toggle = screen.getByRole("button", {
      name: "Ontologia da obra",
    });

    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(
      screen.queryByText("Rastreabilidade Cortex"),
    ).not.toBeInTheDocument();
  });

  it("monta a ontologia somente depois da abertura explícita", async () => {
    const user = userEvent.setup();
    render(<ObrasPage />);

    const toggle = screen.getByRole("button", {
      name: "Ontologia da obra",
    });
    await user.click(toggle);

    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(
      screen.getByText("Rastreabilidade Cortex"),
    ).toBeInTheDocument();
  });
});
