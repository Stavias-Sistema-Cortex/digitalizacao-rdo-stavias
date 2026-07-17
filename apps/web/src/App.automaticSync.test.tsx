import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getSession: vi.fn(),
  hasOnlineSession: vi.fn(),
  useAutomaticSync: vi.fn(),
}));

vi.mock("react", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react")>();

  return {
    ...actual,
    useEffect: vi.fn(),
    useState: vi.fn((initializer: unknown) => [
      typeof initializer === "function"
        ? (initializer as () => unknown)()
        : initializer,
      vi.fn(),
    ]),
  };
});

vi.mock("./features/auth/authSession", () => ({
  AUTH_SESSION_CHANGED_EVENT: "cortex-auth-session-changed",
  getSession: mocks.getSession,
  hasOnlineSession: mocks.hasOnlineSession,
  isAlfa: vi.fn(() => false),
}));

vi.mock("./lib/sync/useAutomaticSync", () => ({
  useAutomaticSync: mocks.useAutomaticSync,
}));

vi.stubGlobal("navigator", { onLine: false });

import App from "./App";

const offlineProfile = {
  colaboradorId: "00000000-0000-4000-8000-000000000001",
  nome: "Operador offline",
  papelAcesso: "BETA",
  escopoGlobal: false,
  obraIds: ["00000000-0000-4000-8000-000000000002"],
  expiraEm: "2099-07-17T12:00:00.000Z",
};

describe("App automatic sync lifecycle", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getSession.mockReturnValue(offlineProfile);
    mocks.hasOnlineSession.mockReturnValue(false);
  });

  it("mounts automatic sync for a valid offline-unlocked session", () => {
    App({});

    expect(mocks.useAutomaticSync).toHaveBeenCalledWith(true);
  });
});
