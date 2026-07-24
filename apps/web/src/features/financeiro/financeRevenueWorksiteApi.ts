import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";

export interface RevenueWorksite {
  id: string;
  codigoContrato: string | null;
  nome: string | null;
  status: string | null;
}

function nullableString(
  value: unknown,
  field: string,
): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value !== "string") {
    throw new Error(`O servidor retornou ${field} inválido para uma obra.`);
  }
  const normalized = value.trim();
  return normalized || null;
}

function revenueWorksite(value: unknown): RevenueWorksite {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("O servidor retornou uma obra inválida.");
  }
  const raw = value as Record<string, unknown>;
  if (typeof raw.id !== "string" || !raw.id.trim()) {
    throw new Error("O servidor retornou uma obra sem identificador.");
  }
  return {
    id: raw.id.trim(),
    codigoContrato: nullableString(raw.codigoContrato, "o contrato"),
    nome: nullableString(raw.nome, "o nome"),
    status: nullableString(raw.status, "o status"),
  };
}

export async function fetchAuthorizedRevenueWorksites(): Promise<
  RevenueWorksite[]
> {
  const response = await apiFetch("/obras/relacionadas");
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(body, response.status));
  }
  if (!Array.isArray(body)) {
    throw new Error("O servidor retornou uma resposta de obras inválida.");
  }
  return body.map(revenueWorksite);
}
