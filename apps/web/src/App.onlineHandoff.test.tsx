// @vitest-environment jsdom

import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  OFFLINE_GRANT_UNAVAILABLE_MESSAGE,
  queueOfflineGrantUnavailableNotice,
} from "./features/auth/authNotice";

const mocks = vi.hoisted(() => ({
  getSession: vi.fn(),
  hasCollaborativeOfflineGrantMetadata: vi.fn(),
  initializeCortexDb: vi.fn(),
  loadOfflineVaultMetadata: vi.fn(),
}));

vi.mock("./features/auth/authSession", () => ({
  AUTH_SESSION_CHANGED_EVENT: "cortex-auth-session-changed",
  getSession: mocks.getSession,
  isAlfa: () => false,
}));

vi.mock("./features/auth/offlineVaultRepository", () => ({
  hasCollaborativeOfflineGrantMetadata:
    mocks.hasCollaborativeOfflineGrantMetadata,
  loadOfflineVaultMetadata: mocks.loadOfflineVaultMetadata,
}));

vi.mock("./lib/db/cortexDb", () => ({
  initializeCortexDb: mocks.initializeCortexDb,
}));

vi.mock("./appAutomaticSync", () => ({
  useAppAutomaticSync: () => undefined,
}));

vi.mock("./features/auth/LoginPage", () => ({
  LoginPage: () => <main data-testid="online-login">Login online</main>,
}));

vi.mock("./features/home/HomePage", () => ({
  HomePage: () => <main data-testid="home-page">Home</main>,
}));

import App from "./App";

const profile = {
  colaboradorId: "00000000-0000-4000-8000-000000000001",
  nome: "Colaborador Córtex",
  papelAcesso: "ALFA" as const,
  escopoGlobal: true,
  obraIds: [],
  expiraEm: "2099-07-14T12:00:00Z",
};

describe("App online authentication handoff", () => {
  let activeSession: typeof profile | null;

  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    activeSession = null;
    mocks.getSession.mockImplementation(() => activeSession);
    mocks.loadOfflineVaultMetadata.mockResolvedValue(null);
    mocks.hasCollaborativeOfflineGrantMetadata.mockResolvedValue(false);
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
  });

  it("prepares local data before routing a newly authenticated live document", async () => {
    mocks.initializeCortexDb.mockReturnValue(new Promise<void>(() => undefined));

    render(<App />);
    expect(await screen.findByTestId("online-login")).toBeInTheDocument();

    activeSession = profile;
    act(() => {
      window.dispatchEvent(new Event("cortex-auth-session-changed"));
    });

    expect(await screen.findByText(
      "Preparando os dados locais protegidos…",
    )).toBeInTheDocument();
    expect(mocks.initializeCortexDb).toHaveBeenCalledTimes(1);
  });

  it("shows a queued offline-grant warning after an in-document CPF login", async () => {
    let resolveInitialization: (() => void) | undefined;
    mocks.initializeCortexDb.mockReturnValue(
      new Promise<void>((resolve) => {
        resolveInitialization = resolve;
      }),
    );

    render(<App />);
    expect(await screen.findByTestId("online-login")).toBeInTheDocument();

    queueOfflineGrantUnavailableNotice();
    activeSession = profile;
    act(() => {
      window.dispatchEvent(new Event("cortex-auth-session-changed"));
    });

    expect(await screen.findByText(
      "Preparando os dados locais protegidos…",
    )).toBeInTheDocument();

    act(() => {
      resolveInitialization?.();
    });

    expect(await screen.findByText(
      OFFLINE_GRANT_UNAVAILABLE_MESSAGE,
    )).toBeInTheDocument();
  });
});
