import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";
import type { FinanceCapabilities } from "./financeiro.types";

const FINANCIAL_PERMISSIONS = new Set([
  "FINANCEIRO_VISUALIZAR",
  "FINANCEIRO_OPERAR",
  "FINANCEIRO_APROVAR",
  "FINANCEIRO_ADMINISTRAR",
]);

function capabilitiesFromBody(body: unknown): FinanceCapabilities {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw new Error("O servidor retornou capacidades financeiras inválidas.");
  }
  const raw = body as Record<string, unknown>;
  if (
    typeof raw.obraId !== "string" ||
    !raw.obraId.trim() ||
    !Array.isArray(raw.permissoes) ||
    !raw.permissoes.every((permission) =>
      typeof permission === "string" &&
      FINANCIAL_PERMISSIONS.has(permission)
    )
  ) {
    throw new Error("O servidor retornou capacidades financeiras inválidas.");
  }
  return {
    obraId: raw.obraId.trim(),
    permissoes: raw.permissoes as FinanceCapabilities["permissoes"],
  };
}

export async function fetchRevenueCapabilities(
  obraId: string,
): Promise<FinanceCapabilities> {
  const params = new URLSearchParams({ obraId });
  const response = await apiFetch(`/financeiro/capacidades?${params}`);
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(body, response.status));
  }
  return capabilitiesFromBody(body);
}
