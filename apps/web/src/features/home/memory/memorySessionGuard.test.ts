import { afterEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  setSession,
  type AuthProfile,
} from "../../auth/authSession";
import {
  captureMemorySessionGuard,
  commitIfMemorySessionCurrent,
  isMemorySessionGuardCurrent,
} from "./memorySessionGuard";
import { runMemoryLedgerRead } from "./useMemoryLedger";

const USER_ID = "00000000-0000-4000-8000-000000000001";
const WORKSITE_A = "00000000-0000-4000-8000-000000000002";
const WORKSITE_B = "00000000-0000-4000-8000-000000000003";

function profile(overrides: Partial<AuthProfile> = {}): AuthProfile {
  return {
    colaboradorId: USER_ID,
    nome: "Encarregado",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [WORKSITE_A],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
    ...overrides,
  };
}

afterEach(() => clearSession());

describe("Memory session guard", () => {
  it("rejects a suspended asynchronous result after same-user worksite scope changes", async () => {
    setSession(profile());
    const guard = captureMemorySessionGuard();
    const commit = vi.fn();
    let release: (() => void) | undefined;
    const suspendedRead = (async () => {
      await new Promise<void>((resolve) => {
        release = resolve;
      });
      return commitIfMemorySessionCurrent(guard, commit);
    })();

    setSession(profile({ obraIds: [WORKSITE_B] }));
    release?.();

    expect(await suspendedRead).toBe(false);
    expect(commit).not.toHaveBeenCalled();
  });

  it("does not commit old items, metadata, coverage, errors or completion after a suspended database read", async () => {
    setSession(profile());
    const session = captureMemorySessionGuard().session;
    const guard = captureMemorySessionGuard();
    let release: ((database: never) => void) | undefined;
    const database = {} as never;
    const repositoryFactory = vi.fn(() => ({
      latestMetadata: vi.fn().mockResolvedValue({ scopeHash: "old-scope" }),
      search: vi.fn().mockResolvedValue({
        items: [{ eventId: "old-item" }],
        totalMatches: 1,
        localStatuses: [],
      }),
    })) as never;
    const onSuccess = vi.fn();
    const onError = vi.fn();
    const onDone = vi.fn();
    const read = runMemoryLedgerRead({
      guard,
      session,
      filters: {},
      visibleLimit: 50,
      online: false,
      isMounted: () => true,
      onSuccess,
      onError,
      onDone,
      openDatabase: () => new Promise((resolve) => {
        release = resolve;
      }),
      repositoryFactory,
    });

    setSession(profile({ obraIds: [WORKSITE_B] }));
    release?.(database);
    await read;

    expect(repositoryFactory).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
    expect(onDone).not.toHaveBeenCalled();
  });

  it.each([
    { papelAcesso: "ALFA" as const, escopoGlobal: true, obraIds: [] },
    { expiraEm: new Date(Date.now() + 120_000).toISOString() },
  ])("rejects role, scope mode, expiry and session-instance rotation", (change) => {
    const initial = profile();
    setSession(initial);
    const guard = captureMemorySessionGuard();
    setSession(profile(change));
    expect(isMemorySessionGuardCurrent(guard)).toBe(false);
  });

  it("rejects a new session instance even when its public values are identical", () => {
    const initial = profile();
    setSession(initial);
    const guard = captureMemorySessionGuard();
    setSession({ ...initial, obraIds: [...initial.obraIds] });
    expect(isMemorySessionGuardCurrent(guard)).toBe(false);
  });
});
