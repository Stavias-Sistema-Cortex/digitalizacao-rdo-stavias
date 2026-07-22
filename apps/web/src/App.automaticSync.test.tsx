import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  hasOnlineSession: vi.fn(),
  useAutomaticSync: vi.fn(),
}));

vi.mock("./features/auth/authSession", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./features/auth/authSession")>()),
  hasOnlineSession: mocks.hasOnlineSession,
}));
vi.mock("./lib/sync/useAutomaticSync", () => ({
  useAutomaticSync: mocks.useAutomaticSync,
}));

import { useAppAutomaticSync } from "./appAutomaticSync";
import type { AuthProfile } from "./features/auth/authSession";

function Probe({ session }: { session: AuthProfile | null }) {
  useAppAutomaticSync(session);
  return null;
}

describe("App automatic sync mounting", () => {
  it("enables automatic sync only for the current online session", () => {
    mocks.hasOnlineSession.mockReturnValue(true);
    const profile = {
      colaboradorId: "00000000-0000-4000-8000-000000000201",
      nome: "Operador",
      papelAcesso: "BETA" as const,
      escopoGlobal: false,
      obraIds: ["00000000-0000-4000-8000-000000000202"],
      expiraEm: "2026-07-22T13:00:00.000Z",
    };

    renderToStaticMarkup(<Probe session={profile} />);
    renderToStaticMarkup(<Probe session={null} />);

    expect(mocks.useAutomaticSync).toHaveBeenNthCalledWith(1, true);
    expect(mocks.useAutomaticSync).toHaveBeenNthCalledWith(2, false);
  });
});
