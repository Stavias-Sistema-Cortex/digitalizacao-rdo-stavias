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
vi.stubGlobal("window", { dispatchEvent });
vi.stubGlobal("BroadcastChannel", FakeBroadcastChannel);

const profile = {
  colaboradorId: "00000000-0000-4000-8000-000000000001",
  nome: "Colaborador Sintético",
  papelAcesso: "BETA" as const,
  expiraEm: "2026-07-14T12:00:00Z",
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

  it("propaga somente LOGOUT entre abas sem perfil ou segredo", async () => {
    const firstTab = await import("./authSession");
    firstTab.setSession(profile);

    vi.resetModules();
    const secondTab = await import("./authSession");
    secondTab.setSession(profile);

    firstTab.clearSession();

    expect(secondTab.getSession()).toBeNull();
    expect(
      broadcastChannels.flatMap((channel) => channel.messages),
    ).toEqual(["LOGOUT"]);
    expect(
      JSON.stringify(
        broadcastChannels.flatMap((channel) => channel.messages),
      ),
    ).not.toContain(profile.colaboradorId);
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
});
