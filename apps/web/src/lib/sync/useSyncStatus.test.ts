// @vitest-environment jsdom

import {
  act,
  cleanup,
  renderHook,
} from "@testing-library/react";
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";

import type {
  OutboxMutationRecord,
  SyncStateRecord,
} from "../db/db.types";

const repositoryMocks = vi.hoisted(() => ({
  getSyncState: vi.fn(),
  listOutboxMutations: vi.fn(),
}));

vi.mock("../db/syncStateRepository", () => ({
  getSyncState: repositoryMocks.getSyncState,
}));

vi.mock("../db/outboxRepository", () => ({
  listOutboxMutations:
    repositoryMocks.listOutboxMutations,
}));

import * as syncStatusModule from "./useSyncStatus";

interface DetermineSyncUiStatusInput {
  isOnline: boolean;
  isSyncing: boolean;
  pendingCount: number;
  syncingCount: number;
  errorCount: number;
  conflictCount: number;
  reviewCount: number;
  lastSyncError: string | null;
}

type DetermineSyncUiStatus = (
  input: DetermineSyncUiStatusInput,
) => string;

const determineSyncUiStatus = (
  syncStatusModule as unknown as {
    determineSyncUiStatus?: DetermineSyncUiStatus;
  }
).determineSyncUiStatus;

const baseInput: DetermineSyncUiStatusInput = {
  isOnline: true,
  isSyncing: false,
  pendingCount: 0,
  syncingCount: 0,
  errorCount: 0,
  conflictCount: 0,
  reviewCount: 0,
  lastSyncError: null,
};

const idleSyncState: SyncStateRecord = {
  key: "default",
  deviceId: "device-1",
  usuarioId: "user-1",
  lastPulledCommitSeq: 0,
  lastAckedCommitSeq: 0,
  isSyncing: false,
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
  syncExecutionLease: null,
};

function rejectedMutation(
  id: string,
  updatedAt: string,
  blockedReason: string | null,
  ultimoErro: string | null,
): OutboxMutationRecord {
  return {
    clientMutationId: id,
    entidadeTipo: "RDO",
    entidadeId: `rdo-${id}`,
    operacao: "CRIAR_RDO",
    baseVersao: null,
    payload: {},
    status: "REJECTED",
    tentativas: 1,
    ultimaTentativaEm: updatedAt,
    ultimoErro,
    conflito: null,
    criadaNoClienteEm: updatedAt,
    updatedAt,
    blockedReason,
  };
}

function conflictedMutation(
  id: string,
  updatedAt: string,
): OutboxMutationRecord {
  return {
    ...rejectedMutation(id, updatedAt, null, "Conflito de versão."),
    status: "CONFLICT",
  };
}

function pendingReplacement(
  id: string,
  causationId: string,
  updatedAt: string,
): OutboxMutationRecord {
  return {
    ...rejectedMutation(id, updatedAt, null, null),
    status: "PENDING",
    causationId,
  } as OutboxMutationRecord;
}

beforeEach(() => {
  Object.defineProperty(window.navigator, "onLine", {
    configurable: true,
    value: true,
  });

  repositoryMocks.getSyncState.mockResolvedValue(
    idleSyncState,
  );
  repositoryMocks.listOutboxMutations.mockResolvedValue(
    [],
  );
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("determineSyncUiStatus", () => {
  it("requires human review for rejected mutations before transient errors", () => {
    expect(determineSyncUiStatus).toBeTypeOf(
      "function",
    );

    expect(
      determineSyncUiStatus?.({
        ...baseInput,
        reviewCount: 1,
        errorCount: 1,
      }),
    ).toBe("REVIEW");
  });

  it("preserves conflict priority over human review", () => {
    expect(
      determineSyncUiStatus?.({
        ...baseInput,
        conflictCount: 1,
        reviewCount: 1,
      }),
    ).toBe("CONFLICT");
  });

  it("keeps transient errors distinct without rejected mutations", () => {
    expect(
      determineSyncUiStatus?.({
        ...baseInput,
        errorCount: 1,
      }),
    ).toBe("ERROR");
  });
});

describe("useSyncStatus", () => {
  it("reports rejected mutations with count and the latest human-review reason", async () => {
    repositoryMocks.listOutboxMutations.mockResolvedValue([
      rejectedMutation(
        "older",
        "2026-07-22T10:00:00.000Z",
        null,
        "Motivo antigo",
      ),
      rejectedMutation(
        "newer",
        "2026-07-22T11:00:00.000Z",
        "Vínculo da obra precisa ser revisado",
        "Falha genérica",
      ),
    ]);

    const { result } = renderHook(() =>
      syncStatusModule.useSyncStatus(),
    );

    await act(async () => {
      await result.current.refresh();
    });

    expect(result.current.snapshot).toMatchObject({
      status: "REVIEW",
      reviewCount: 2,
      reviewReason:
        "Vínculo da obra precisa ser revisado",
    });
  });

  /**
   * Reconciliar um conflito cria uma substituta e deixa a original onde está.
   * Contá-la prendia a tela em "Conflito de versão" para sempre — e, como o
   * conflito é consultado antes de tudo, escondia pendência e revisão embaixo.
   */
  it("não conta o conflito que já cedeu lugar a uma substituta", async () => {
    repositoryMocks.listOutboxMutations.mockResolvedValue([
      conflictedMutation("original", "2026-07-22T10:00:00.000Z"),
      pendingReplacement(
        "substituta",
        "original",
        "2026-07-22T10:00:01.000Z",
      ),
    ]);

    const { result } = renderHook(() =>
      syncStatusModule.useSyncStatus(),
    );

    await act(async () => {
      await result.current.refresh();
    });

    // O trabalho não sumiu: mudou de rótulo, e a substituta responde por ele.
    expect(result.current.snapshot).toMatchObject({
      status: "PENDING",
      conflictCount: 0,
      pendingCount: 1,
    });
  });

  /**
   * A metade que não pode ceder: sem substituta, a original ainda é a única
   * cópia do que aconteceu em campo. Silenciá-la faria a tela dizer que subiu o
   * que não subiu.
   */
  it("continua contando o conflito que não tem substituta", async () => {
    repositoryMocks.listOutboxMutations.mockResolvedValue([
      conflictedMutation("sozinho", "2026-07-22T10:00:00.000Z"),
    ]);

    const { result } = renderHook(() =>
      syncStatusModule.useSyncStatus(),
    );

    await act(async () => {
      await result.current.refresh();
    });

    expect(result.current.snapshot).toMatchObject({
      status: "CONFLICT",
      conflictCount: 1,
    });
  });

  it("não conta a original marcada como superada", async () => {
    repositoryMocks.listOutboxMutations.mockResolvedValue([
      rejectedMutation(
        "superada",
        "2026-07-22T10:00:00.000Z",
        "SUPERSEDED_BY:11111111-1111-4111-8111-111111111111",
        "Conflito substituído por envelope canônico reconciliado.",
      ),
    ]);

    const { result } = renderHook(() =>
      syncStatusModule.useSyncStatus(),
    );

    await act(async () => {
      await result.current.refresh();
    });

    expect(result.current.snapshot).toMatchObject({
      status: "SYNCED",
      reviewCount: 0,
      reviewReason: null,
    });
  });
});
