import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getSession: vi.fn(),
  hasOnlineSession: vi.fn(),
  getSyncState: vi.fn(),
  updateSyncState: vi.fn(),
  registerDeviceApi: vi.fn(),
}));

vi.mock("../../features/auth/authSession", () => ({
  getSession: mocks.getSession,
  hasOnlineSession: mocks.hasOnlineSession,
}));
vi.mock("../db/syncStateRepository", () => ({
  getSyncState: mocks.getSyncState,
  updateSyncState: mocks.updateSyncState,
}));
vi.mock("./syncApiClient", () => ({
  registerDeviceApi: mocks.registerDeviceApi,
}));

vi.stubGlobal("navigator", {
  platform: "Ambiente Sintético",
  userAgent: "Teste",
});

import { ensureRegisteredDevice } from "./registerDevice";

describe("ensureRegisteredDevice authorization", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getSyncState.mockResolvedValue({});
    mocks.getSession.mockReturnValue({
      colaboradorId: "00000000-0000-4000-8000-000000000001",
    });
    mocks.hasOnlineSession.mockReturnValue(false);
  });

  it("não registra dispositivo sem uma sessão online", async () => {
    await expect(ensureRegisteredDevice()).rejects.toThrow(
      "Faça login novamente para sincronizar com o servidor.",
    );
    expect(mocks.registerDeviceApi).not.toHaveBeenCalled();
    expect(mocks.updateSyncState).not.toHaveBeenCalled();
  });
});
