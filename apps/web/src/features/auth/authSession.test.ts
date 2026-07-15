import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { getSession, setSession } from "./authSession";

const SESSION_KEY = "cortex.auth.sessao";

function memoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() {
      return values.size;
    },
    clear() {
      values.clear();
    },
    getItem(key) {
      return values.get(key) ?? null;
    },
    key(index) {
      return [...values.keys()][index] ?? null;
    },
    removeItem(key) {
      values.delete(key);
    },
    setItem(key, value) {
      values.set(key, value);
    },
  };
}

beforeEach(() => {
  vi.stubGlobal("localStorage", memoryStorage());
  vi.stubGlobal("window", {
    dispatchEvent: vi.fn(),
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("authSession", () => {
  it("persiste somente a identidade mascarada e o token, nunca o CPF bruto", () => {
    setSession({
      colaboradorId: "colaborador-1",
      nome: "Ana",
      cpfMascarado: "***.***.***-35",
      cpf: "11144477735",
      perfil: "Operacional",
      papelAcesso: "BETA",
      token: "jwt",
      origem: "online",
      autenticadoEm: "2026-07-15T12:00:00Z",
    } as never);

    expect(localStorage.getItem(SESSION_KEY)).not.toContain("11144477735");
    expect(localStorage.getItem(SESSION_KEY)).not.toContain('"cpf"');
    expect(getSession()?.colaboradorId).toBe("colaborador-1");
  });

  it("higieniza uma sessão legada que ainda contenha CPF bruto", () => {
    localStorage.setItem(
      SESSION_KEY,
      JSON.stringify({
        colaboradorId: "colaborador-1",
        nome: "Ana",
        cpfMascarado: "***.***.***-35",
        cpf: "11144477735",
        perfil: "Operacional",
        papelAcesso: "BETA",
        token: "jwt",
        origem: "online",
        autenticadoEm: "2026-07-15T12:00:00Z",
      }),
    );

    expect(getSession()?.colaboradorId).toBe("colaborador-1");
    expect(localStorage.getItem(SESSION_KEY)).not.toContain("11144477735");
  });
});
