import { beforeEach, describe, expect, it, vi } from "vitest";

const legacyValues = new Map<string, string>();
const dispatchEvent = vi.fn();
const broadcastChannels: FakeBroadcastChannel[] = [];

class FakeBroadcastChannel {
  onmessage: ((event: MessageEvent) => void) | null = null;
  readonly messages: unknown[] = [];

  constructor(readonly name: string) {
    broadcastChannels.push(this);
  }

  postMessage(message: unknown): void {
    this.messages.push(message);
    for (const channel of broadcastChannels) {
      if (channel !== this && channel.name === this.name) {
        channel.onmessage?.({ data: message } as MessageEvent);
      }
    }
  }

  close(): void {}
}

vi.stubGlobal("localStorage", {
  getItem: (key: string) => legacyValues.get(key) ?? null,
  setItem: (key: string, value: string) => legacyValues.set(key, value),
  removeItem: (key: string) => legacyValues.delete(key),
});
vi.stubGlobal("window", {
  dispatchEvent,
  localStorage: {
    getItem: (key: string) => legacyValues.get(key) ?? null,
    setItem: (key: string, value: string) => legacyValues.set(key, value),
    removeItem: (key: string) => legacyValues.delete(key),
  },
});
vi.stubGlobal("BroadcastChannel", FakeBroadcastChannel);

const profile = {
  colaboradorId: "00000000-0000-4000-8000-000000000001",
  nome: "Colaborador Sintético",
  papelAcesso: "BETA" as const,
  escopoGlobal: false,
  obraIds: ["00000000-0000-4000-8000-000000000002"],
  expiraEm: "2099-07-14T12:00:00Z",
};

describe("authSession", () => {
  beforeEach(() => {
    vi.resetModules();
    legacyValues.clear();
    broadcastChannels.length = 0;
    dispatchEvent.mockClear();
  });

  it("mantém o perfil somente na memória do módulo", async () => {
    const first = await import("./authSession");
    first.setSession(profile);

    expect(first.getSession()).toEqual(profile);
    expect(first.hasOnlineSession()).toBe(true);
    expect(legacyValues.size).toBe(0);

    vi.resetModules();
    const reloaded = await import("./authSession");
    expect(reloaded.getSession()).toBeNull();
  });

  it("purga JWT, CPF e Bloom legados sem tentar interpretá-los", async () => {
    legacyValues.set("cortex.auth.sessao", "{malformado");
    legacyValues.set("cortex.auth.cpfFilter", "material-legado");

    const session = await import("./authSession");
    session.purgeLegacyAuthStorage();

    expect(legacyValues.has("cortex.auth.sessao")).toBe(false);
    expect(legacyValues.has("cortex.auth.cpfFilter")).toBe(false);
  });

  it("dispara o evento existente ao criar e limpar a sessão", async () => {
    const session = await import("./authSession");
    session.setSession(profile);
    session.clearSession();

    expect(dispatchEvent).toHaveBeenCalledTimes(2);
    expect(session.getSession()).toBeNull();
    expect(session.hasOnlineSession()).toBe(false);
  });

  it("remove o contexto operacional local ao encerrar a sessão", async () => {
    legacyValues.set("cortex:stavia:chat:operacional", "conteúdo privado");
    legacyValues.set("cortex:stavia:last-context", "obra privada");
    const session = await import("./authSession");
    session.setSession(profile);

    session.clearSession();

    expect(legacyValues.has("cortex:stavia:chat:operacional")).toBe(false);
    expect(legacyValues.has("cortex:stavia:last-context")).toBe(false);
  });

  it("clears session memory even when retired local-storage cleanup is unavailable", async () => {
    const session = await import("./authSession");
    session.setSession(profile);
    const removeItem = vi.spyOn(window.localStorage, "removeItem")
      .mockImplementation(() => {
        throw new Error("storage unavailable");
      });

    expect(() => session.clearSession()).not.toThrow();
    expect(session.getSession()).toBeNull();

    removeItem.mockRestore();
  });

  it("encerra a sessão da aba anterior quando um novo login substitui o cookie compartilhado", async () => {
    const firstTab = await import("./authSession");
    firstTab.setSession(profile);

    vi.resetModules();
    const secondTab = await import("./authSession");
    secondTab.setSession(profile);

    expect(firstTab.getSession()).toBeNull();
    expect(secondTab.getSession()).toEqual(profile);
    expect(
      broadcastChannels.flatMap((channel) => channel.messages),
    ).toEqual(["SESSION_REPLACED", "SESSION_REPLACED"]);
    expect(
      JSON.stringify(
        broadcastChannels.flatMap((channel) => channel.messages),
      ),
    ).not.toContain(profile.colaboradorId);
  });

  it("propaga LOGOUT entre abas sem perfil ou segredo", async () => {
    const firstTab = await import("./authSession");
    firstTab.setSession(profile);

    vi.resetModules();
    const secondTab = await import("./authSession");
    secondTab.setSession(profile);

    firstTab.clearSession();

    expect(secondTab.getSession()).toBeNull();
    expect(
      broadcastChannels.flatMap((channel) => channel.messages),
    ).toEqual(["SESSION_REPLACED", "LOGOUT", "SESSION_REPLACED"]);
    expect(
      JSON.stringify(
        broadcastChannels.flatMap((channel) => channel.messages),
      ),
    ).not.toContain(profile.colaboradorId);
  });

  it("clears only the current document when a remote session is rejected", async () => {
    const firstTab = await import("./authSession");
    firstTab.setSession(profile);

    vi.resetModules();
    const secondTab = await import("./authSession");
    secondTab.setSession(profile);

    firstTab.clearSessionForCurrentDocument();

    expect(firstTab.getSession()).toBeNull();
    expect(secondTab.getSession()).toEqual(profile);
    expect(
      broadcastChannels.flatMap((channel) => channel.messages),
    ).toEqual(["SESSION_REPLACED", "SESSION_REPLACED"]);
  });

  it("reconhece ALFA somente com valor canônico exato", async () => {
    const session = await import("./authSession");
    expect(session.isAlfa({ ...profile, papelAcesso: "ALFA" })).toBe(true);
    expect(
      session.isAlfa({
        ...profile,
        papelAcesso: "alfa" as "ALFA",
      }),
    ).toBe(false);
  });

  it("expõe perfil offline sem habilitar sincronização online", async () => {
    const session = await import("./authSession");
    session.setOfflineSession(profile);

    expect(session.getSession()).toEqual(profile);
    expect(session.hasOfflineSession()).toBe(true);
    expect(session.hasOnlineSession()).toBe(false);

    session.setSession({ ...profile, nome: "Sessão online" });
    expect(session.getSession()?.nome).toBe("Sessão online");
    expect(session.hasOnlineSession()).toBe(true);
    expect(session.hasOfflineSession()).toBe(false);
  });

  it("encerra uma sessão offline assim que o grant expira", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-14T12:00:00Z"));
    const session = await import("./authSession");
    session.setOfflineSession({
      ...profile,
      expiraEm: "2026-07-14T12:00:01Z",
    });

    await vi.advanceTimersByTimeAsync(1_001);

    expect(session.getSession()).toBeNull();
    expect(session.hasOfflineSession()).toBe(false);
    expect(dispatchEvent).toHaveBeenCalled();
    vi.useRealTimers();
  });

  it("usa proprietário e escopo canônicos para particionar o cache", async () => {
    const session = await import("./authSession");
    session.setSession(profile);

    expect(session.requireDataScope()).toEqual({
      ownerId: profile.colaboradorId,
      scopeMaterial: `BETA:${profile.obraIds[0]}`,
    });

    session.setSession({
      ...profile,
      papelAcesso: "ALFA",
      escopoGlobal: true,
      obraIds: [],
    });
    expect(session.requireDataScope().scopeMaterial).toBe("ALFA:GLOBAL");
  });
});
