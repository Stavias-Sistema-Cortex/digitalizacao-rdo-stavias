import type { FinanceCapabilities } from "./financeiro.types";
import { fetchRevenueCapabilities } from "./financeRevenueAccessApi";
import {
  cacheFinanceCapabilities,
  getCachedFinanceCapabilities,
} from "./servicePriceRepository";

interface FinanceCapabilityDependencies {
  online?: boolean;
  fetchRemote?: (obraId: string) => Promise<FinanceCapabilities>;
  cache?: (capabilities: FinanceCapabilities) => Promise<void>;
  readCache?: (obraId: string) => Promise<FinanceCapabilities | null>;
}

export async function resolveFinanceCapabilities(
  obraId: string,
  dependencies: FinanceCapabilityDependencies = {},
): Promise<FinanceCapabilities> {
  const online = dependencies.online ??
    (typeof navigator === "undefined" || navigator.onLine);
  const fetchRemote = dependencies.fetchRemote ?? fetchRevenueCapabilities;
  const cache = dependencies.cache ?? cacheFinanceCapabilities;
  const readCache = dependencies.readCache ?? getCachedFinanceCapabilities;
  let remoteFailure: unknown = null;

  if (online) {
    try {
      const grants = await fetchRemote(obraId);
      if (grants.obraId !== obraId) {
        throw new Error("Escopo financeiro divergente na resposta do servidor.");
      }
      await cache(grants);
      return grants;
    } catch (reason: unknown) {
      if (reason instanceof Error && /escopo financeiro divergente/i.test(reason.message)) {
        throw reason;
      }
      remoteFailure = reason;
    }
  }

  const cached = await readCache(obraId);
  if (cached?.obraId === obraId) {
    return cached;
  }
  if (remoteFailure instanceof Error) {
    throw remoteFailure;
  }
  throw new Error(
    "Permissões financeiras não estão disponíveis offline para esta sessão.",
  );
}
