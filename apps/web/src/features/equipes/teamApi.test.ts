import { beforeEach, describe, expect, it, vi } from "vitest";

const { apiFetch } = vi.hoisted(() => ({ apiFetch: vi.fn() }));

vi.mock("../../lib/api/apiClient", () => ({
  apiFetch,
  readResponseBody: async (response: Response) => JSON.parse(await response.text()),
  responseErrorMessage: (body: { message?: string }, status: number) => body.message ?? `HTTP ${status}`,
}));

import { addTeamMember, fetchTeams } from "./teamApi";

beforeEach(() => apiFetch.mockReset());

describe("teamApi", () => {
  it("codifica filtros temporais e de escopo da listagem", async () => {
    apiFetch.mockResolvedValue(new Response(JSON.stringify({
      items: [], totalElements: 0, page: 0, size: 20, totalPages: 0,
    }), { status: 200 }));

    await fetchTeams({
      obraId: "obra/1",
      texto: "drenagem norte",
      funcaoOperacionalId: "role-1",
      status: "ATIVA",
      vigenteEm: "2026-07-15T12:00:00",
    });

    expect(apiFetch.mock.calls[0][0]).toBe(
      "/equipes?obraId=obra%2F1&texto=drenagem+norte&funcaoOperacionalId=role-1&status=ATIVA&vigenteEm=2026-07-15T12%3A00%3A00",
    );
  });

  it("envia concessão explícita de acesso ao adicionar membro", async () => {
    apiFetch.mockResolvedValue(new Response(JSON.stringify({ id: "member-1" }), { status: 200 }));

    await addTeamMember("team-1", {
      id: "member-1",
      colaboradorId: "user-1",
      funcaoOperacionalId: "role-1",
      responsavel: true,
      inicioEm: "2026-07-15T00:00:00",
      baseVersao: null,
      motivo: "Mobilização da frente",
      concederAcessoObra: true,
    });

    const options = apiFetch.mock.calls[0][1] as RequestInit;
    expect(JSON.parse(String(options.body))).toMatchObject({
      colaboradorId: "user-1",
      concederAcessoObra: true,
      motivo: "Mobilização da frente",
    });
  });
});
