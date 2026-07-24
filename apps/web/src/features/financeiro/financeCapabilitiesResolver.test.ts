import { describe, expect, it, vi } from "vitest";

import type { FinanceCapabilities } from "./financeiro.types";
import { resolveFinanceCapabilities } from "./financeCapabilitiesResolver";

const OBRA_ID = "00000000-0000-4000-8000-000000000101";
const GRANTS: FinanceCapabilities = {
  obraId: OBRA_ID,
  permissoes: ["FINANCEIRO_VISUALIZAR"],
};

describe("finance capability resolution", () => {
  it("persists online grants before exposing them", async () => {
    const cache = vi.fn().mockResolvedValue(undefined);

    const result = await resolveFinanceCapabilities(OBRA_ID, {
      online: true,
      fetchRemote: vi.fn().mockResolvedValue(GRANTS),
      cache,
      readCache: vi.fn(),
    });

    expect(cache).toHaveBeenCalledWith(GRANTS);
    expect(result).toEqual(GRANTS);
  });

  it("falls back only to the exact cached session grants", async () => {
    const result = await resolveFinanceCapabilities(OBRA_ID, {
      online: false,
      fetchRemote: vi.fn(),
      cache: vi.fn(),
      readCache: vi.fn().mockResolvedValue(GRANTS),
    });

    expect(result).toEqual(GRANTS);
  });

  it("fails closed when offline grants are unavailable or mismatched", async () => {
    await expect(resolveFinanceCapabilities(OBRA_ID, {
      online: false,
      fetchRemote: vi.fn(),
      cache: vi.fn(),
      readCache: vi.fn().mockResolvedValue(null),
    })).rejects.toThrow(/permissões financeiras.*offline/i);

    await expect(resolveFinanceCapabilities(OBRA_ID, {
      online: true,
      fetchRemote: vi.fn().mockResolvedValue({
        ...GRANTS,
        obraId: "00000000-0000-4000-8000-000000000999",
      }),
      cache: vi.fn(),
      readCache: vi.fn(),
    })).rejects.toThrow(/escopo financeiro divergente/i);
  });
});
