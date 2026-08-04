// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";

const DONO = "10000000-0000-4000-8000-000000000001";
const OUTRA_PESSOA = "10000000-0000-4000-8000-000000000002";

const fetchIsolatedSessionOwner = vi.hoisted(() => vi.fn());
const setSession = vi.hoisted(() => vi.fn());
const clearOfflineGrant = vi.hoisted(() => vi.fn());
const clearRemoteSessionIsolation = vi.hoisted(() => vi.fn());
const estado = vi.hoisted(() => ({
  offline: true,
  dono: "10000000-0000-4000-8000-000000000001" as string | null,
}));

vi.mock("../../lib/api/apiClient", () => ({
  fetchIsolatedSessionOwner,
  readResponseBody: (response: { corpo: unknown }) =>
    Promise.resolve(response.corpo),
}));
vi.mock("./authApi", () => ({
  parseAuthProfile: (corpo: unknown) => {
    if (!corpo) throw new Error("perfil inválido");
    return corpo;
  },
}));
vi.mock("./authSession", () => ({
  getSession: () => (estado.dono ? { colaboradorId: estado.dono } : null),
  hasOfflineSession: () => estado.offline,
  setSession,
}));
vi.mock("./offlineVault", () => ({ clearOfflineGrant }));
vi.mock("./remoteSessionIsolation", () => ({ clearRemoteSessionIsolation }));

const { retomarSessaoOnline } = await import("./retomadaDaSessao");

function resposta(status: number, corpo: unknown = null) {
  return { status, ok: status >= 200 && status < 300, corpo };
}

beforeEach(() => {
  fetchIsolatedSessionOwner.mockReset();
  setSession.mockReset();
  clearOfflineGrant.mockReset();
  clearRemoteSessionIsolation.mockReset();
  estado.offline = true;
  estado.dono = DONO;
});

/**
 * A volta do modo offline para o online.
 *
 * O isolamento existe porque, depois de os dados serem abertos offline, o
 * cookie HttpOnly deixa de provar que pertence a quem está no aparelho — pode
 * ter sobrado de outra pessoa. A pergunta que resolve isso é uma só: de quem
 * é este cookie? Nada aqui solta o isolamento sem essa resposta.
 */
describe("retomada da sessão online", () => {
  it("volta quando o servidor confirma a mesma pessoa", async () => {
    fetchIsolatedSessionOwner.mockResolvedValue(
      resposta(200, { colaboradorId: DONO, nome: "Fulano" }),
    );

    expect(await retomarSessaoOnline()).toBe("RETOMADA");
    expect(clearRemoteSessionIsolation).toHaveBeenCalled();
    expect(setSession).toHaveBeenCalledWith(
      expect.objectContaining({ colaboradorId: DONO }),
    );
  });

  /**
   * O caso exato que o isolamento existe para barrar: alguém abre os dados
   * com o próprio CPF num aparelho onde sobrou a sessão de outra pessoa.
   * Aceitar esse cookie faria uma pessoa agir como a outra.
   */
  it("não solta o isolamento quando o cookie é de outra pessoa", async () => {
    fetchIsolatedSessionOwner.mockResolvedValue(
      resposta(200, { colaboradorId: OUTRA_PESSOA, nome: "Beltrano" }),
    );

    expect(await retomarSessaoOnline()).toBe("PRECISA_LOGIN");
    expect(clearRemoteSessionIsolation).not.toHaveBeenCalled();
    expect(setSession).not.toHaveBeenCalled();
  });

  it("pede login quando o servidor não reconhece mais o cookie", async () => {
    fetchIsolatedSessionOwner.mockResolvedValue(resposta(401));

    expect(await retomarSessaoOnline()).toBe("PRECISA_LOGIN");
    expect(clearRemoteSessionIsolation).not.toHaveBeenCalled();
  });

  /**
   * Sem transporte não há o que concluir. O aparelho segue offline, com os
   * dados abertos, e a próxima volta de rede tenta de novo — em vez de
   * declarar que a pessoa precisa se autenticar por causa de um túnel.
   */
  it("continua offline quando não há resposta", async () => {
    fetchIsolatedSessionOwner.mockRejectedValue(new Error("sem rede"));

    expect(await retomarSessaoOnline()).toBe("SEM_REDE");
    expect(clearRemoteSessionIsolation).not.toHaveBeenCalled();
    expect(setSession).not.toHaveBeenCalled();
  });

  it("não faz nada quando a sessão já é online", async () => {
    estado.offline = false;

    expect(await retomarSessaoOnline()).toBe("NAO_APLICAVEL");
    expect(fetchIsolatedSessionOwner).not.toHaveBeenCalled();
  });

  /** Resposta que não é identidade não autoriza soltar nada. */
  it("pede login quando o servidor responde algo ilegível", async () => {
    fetchIsolatedSessionOwner.mockResolvedValue(resposta(200, null));

    expect(await retomarSessaoOnline()).toBe("PRECISA_LOGIN");
    expect(clearRemoteSessionIsolation).not.toHaveBeenCalled();
  });
});
