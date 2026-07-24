import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  current: "session-a",
  state: vi.fn(async () => ({
    key: "default",
    deviceId: "device",
    usuarioId: "user",
    lastPulledCommitSeq: 0,
    lastAckedCommitSeq: 0,
  })),
  update: vi.fn(async () => undefined),
  pullApi: vi.fn(),
  applyPull: vi.fn(async () => 1),
  registerApi: vi.fn(),
  ackApi: vi.fn(),
}));

vi.mock("../db/syncStateRepository", () => ({
  getSyncState: mocks.state,
  updateSyncState: mocks.update,
}));
vi.mock("./syncApiClient", () => ({
  pullEventsApi: mocks.pullApi,
  registerDeviceApi: mocks.registerApi,
  ackCursorApi: mocks.ackApi,
}));
vi.mock("./syncStorage", () => ({
  applyPulledEventsAtomically: mocks.applyPull,
}));
vi.mock("./syncSession", () => ({
  assertSyncSession: (guard: { fingerprint: string }) => {
    if (guard.fingerprint !== mocks.current) {
      throw new Error("A sessão mudou durante a sincronização.");
    }
  },
}));
vi.mock("../../features/auth/authSession", () => ({
  getSession: () => ({ colaboradorId: "user" }),
  hasOnlineSession: () => true,
}));
vi.stubGlobal("navigator", {
  platform: "Test",
  userAgent: "Test",
});

import { acknowledgeCurrentCursor } from "./ackCursor";
import { pullEvents } from "./pullEvents";
import { ensureRegisteredDevice } from "./registerDevice";

const guard = { fingerprint: "session-a", userId: "user" };

describe("sync transport session boundary", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.current = "session-a";
  });

  it("does not apply a pull page after its session changes during fetch", async () => {
    mocks.pullApi.mockImplementationOnce(async () => {
      mocks.current = "session-b";
      return { eventos: [], nextCommitSeq: 0, hasMore: false };
    });

    await expect(pullEvents("device", guard)).rejects.toThrow(
      "A sessão mudou durante a sincronização.",
    );
    expect(mocks.applyPull).not.toHaveBeenCalled();
  });

  it("does not persist a registered device after its session changes", async () => {
    mocks.registerApi.mockImplementationOnce(async () => {
      mocks.current = "session-b";
      return { id: "device" };
    });

    await expect(ensureRegisteredDevice(guard)).rejects.toThrow(
      "A sessão mudou durante a sincronização.",
    );
    expect(mocks.update).not.toHaveBeenCalled();
  });

  it("does not persist an ack after its session changes", async () => {
    mocks.state.mockResolvedValueOnce({
      key: "default",
      deviceId: "device",
      usuarioId: "user",
      lastPulledCommitSeq: 2,
      lastAckedCommitSeq: 0,
    });
    mocks.ackApi.mockImplementationOnce(async () => {
      mocks.current = "session-b";
      return { ultimoEventoRecebidoCommitSeq: 2 };
    });

    await expect(acknowledgeCurrentCursor("device", guard)).rejects.toThrow(
      "A sessão mudou durante a sincronização.",
    );
    expect(mocks.update).not.toHaveBeenCalled();
  });
});
