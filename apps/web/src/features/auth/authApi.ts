import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";
import type { CpfFilter } from "./cpfFilter";

export type LoginProfile = {
  colaboradorId: string | null;
  nome: string | null;
  cpfMascarado: string | null;
  perfil: string | null;
  grupo: string | null;
  papelAcesso: string | null;
  token: string | null;
};

export type LoginOnlineResult =
  | { ok: true; profile: LoginProfile }
  | { ok: false; status: number; message: string };

/** Validação exata do CPF no servidor (POST /api/auth/login). */
export async function loginOnline(
  cpfDigits: string,
): Promise<LoginOnlineResult> {
  const response = await apiFetch("/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ cpf: cpfDigits, senha: cpfDigits }),
  });

  const body = await readResponseBody(response);

  if (response.ok) {
    const data = (body ?? {}) as Record<string, unknown>;
    return {
      ok: true,
      profile: {
        colaboradorId: asString(data.colaboradorId),
        nome: asString(data.nome),
        cpfMascarado: asString(data.cpfMascarado),
        perfil: asString(data.perfil),
        grupo: asString(data.grupo),
        papelAcesso: asString(data.papelAcesso),
        token: asString(data.token),
      },
    };
  }

  return {
    ok: false,
    status: response.status,
    message: responseErrorMessage(body, response.status),
  };
}

/** Baixa o filtro de Bloom dos CPFs ativos (GET /api/auth/cpf-filter). */
export async function fetchCpfFilter(): Promise<CpfFilter> {
  const response = await apiFetch("/auth/cpf-filter");
  const body = await readResponseBody(response);

  if (!response.ok) {
    throw new Error(responseErrorMessage(body, response.status));
  }

  return body as CpfFilter;
}

function asString(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null;
}
