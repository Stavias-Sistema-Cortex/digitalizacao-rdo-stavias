// @vitest-environment jsdom

import {
  cleanup,
  render,
  screen,
} from "@testing-library/react";
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";

import type { SyncStatusSnapshot } from "../lib/sync/useSyncStatus";

const syncStatusMocks = vi.hoisted(() => ({
  refresh: vi.fn(),
  useSyncStatus: vi.fn(),
}));

vi.mock("../lib/sync/useSyncStatus", async (importOriginal) => {
  const original =
    await importOriginal<
      typeof import("../lib/sync/useSyncStatus")
    >();

  return {
    ...original,
    useSyncStatus: syncStatusMocks.useSyncStatus,
  };
});

import { SyncStatusBanner } from "./SyncStatusBanner";
import { SyncStateStrip } from "./institutional/SyncStateStrip";

const reviewSnapshot: SyncStatusSnapshot = {
  status: "REVIEW",
  isOnline: true,
  pendingCount: 0,
  syncingCount: 0,
  errorCount: 0,
  conflictCount: 0,
  reviewCount: 2,
  insistindoCount: 0,
  reenviaveisCount: 2,
  reviewReason:
    "Vínculo da obra precisa ser revisado",
  lastSyncCompletedAt: null,
  lastSyncError: null,
  isLoading: false,
};

beforeEach(() => {
  syncStatusMocks.useSyncStatus.mockReturnValue({
    snapshot: reviewSnapshot,
    refresh: syncStatusMocks.refresh,
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("human-review sync presentation", () => {
  it("keeps the current sync chip while announcing human review", () => {
    render(<SyncStatusBanner />);

    expect(
      screen.getByRole("button", {
        name: "Sincronização: Revisão necessária",
      }),
    ).toBeInTheDocument();
  });

  it("shows review count and reason in the institutional strip", () => {
    render(
      <SyncStateStrip snapshot={reviewSnapshot} />,
    );

    expect(
      screen.getByText("Revisão necessária"),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "2 registros exigem revisão",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Vínculo da obra precisa ser revisado",
      ),
    ).toBeInTheDocument();
  });
});
