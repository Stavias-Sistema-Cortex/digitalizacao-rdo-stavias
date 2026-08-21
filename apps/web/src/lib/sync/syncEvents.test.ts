// @vitest-environment jsdom

import { afterEach, describe, expect, it, vi } from "vitest";

/**
 * O aviso de fim de ciclo precisa atravessar abas.
 *
 * Só uma aba executa a sincronização (lease). O aviso era um evento de
 * `window`, que morre na aba onde nasceu: as outras janelas da mesma máquina —
 * o caso clássico é a apresentação com o app aberto duas vezes — continuavam
 * mostrando o retrato velho até o próprio intervalo delas vencer, e o trecho
 * removido numa janela demorava a sumir da outra.
 */

const channels = new Set<FakeBroadcastChannel>();

class FakeBroadcastChannel {
  readonly name: string;
  private listeners = new Set<(event: MessageEvent<unknown>) => void>();
  private closed = false;

  constructor(name: string) {
    this.name = name;
    channels.add(this);
  }

  addEventListener(
    _type: "message",
    listener: (event: MessageEvent<unknown>) => void,
  ): void {
    this.listeners.add(listener);
  }

  removeEventListener(
    _type: "message",
    listener: (event: MessageEvent<unknown>) => void,
  ): void {
    this.listeners.delete(listener);
  }

  postMessage(data: unknown): void {
    for (const channel of channels) {
      if (channel === this || channel.closed || channel.name !== this.name) {
        continue;
      }
      for (const listener of [...channel.listeners]) {
        listener({ data } as MessageEvent<unknown>);
      }
    }
  }

  close(): void {
    this.closed = true;
    this.listeners.clear();
    channels.delete(this);
  }
}

afterEach(() => {
  channels.clear();
  vi.unstubAllGlobals();
  vi.resetModules();
});

describe("aviso de sincronização entre abas", () => {
  it("reapresenta a outra aba assim que o ciclo desta termina", async () => {
    vi.stubGlobal("BroadcastChannel", FakeBroadcastChannel);
    const { SYNC_COMPLETED_EVENT, announceSyncCompleted } = await import(
      "./syncEvents"
    );
    // A "outra aba": um canal com o mesmo nome, só ouvindo.
    const recebidos: unknown[] = [];
    const espelho = new FakeBroadcastChannel("cortex-sync-completed-v1");
    espelho.addEventListener("message", (event) => {
      recebidos.push(event.data);
    });
    const locais: Event[] = [];
    const listener = (event: Event) => locais.push(event);
    window.addEventListener(SYNC_COMPLETED_EVENT, listener);

    announceSyncCompleted();

    window.removeEventListener(SYNC_COMPLETED_EVENT, listener);
    expect(locais).toHaveLength(1);
    expect(recebidos).toEqual([{ type: SYNC_COMPLETED_EVENT }]);
  });

  it("a aba inscrita redespacha o evento local sem ecoar no canal", async () => {
    vi.stubGlobal("BroadcastChannel", FakeBroadcastChannel);
    const {
      SYNC_COMPLETED_EVENT,
      subscribeToSyncCompletedBroadcast,
    } = await import("./syncEvents");

    const unsubscribe = subscribeToSyncCompletedBroadcast();
    const locais: Event[] = [];
    const listener = (event: Event) => locais.push(event);
    window.addEventListener(SYNC_COMPLETED_EVENT, listener);

    // A outra aba anuncia pelo canal.
    const outraAba = new FakeBroadcastChannel("cortex-sync-completed-v1");
    const devolvidos: unknown[] = [];
    outraAba.addEventListener("message", (event) =>
      devolvidos.push(event.data),
    );
    outraAba.postMessage({ type: SYNC_COMPLETED_EVENT });

    window.removeEventListener(SYNC_COMPLETED_EVENT, listener);
    unsubscribe();
    expect(locais).toHaveLength(1);
    // Nada volta ao canal: sem eco entre abas.
    expect(devolvidos).toHaveLength(0);
  });

  it("sem BroadcastChannel o evento local continua saindo", async () => {
    vi.stubGlobal("BroadcastChannel", undefined);
    const { SYNC_COMPLETED_EVENT, announceSyncCompleted } = await import(
      "./syncEvents"
    );
    const locais: Event[] = [];
    const listener = (event: Event) => locais.push(event);
    window.addEventListener(SYNC_COMPLETED_EVENT, listener);

    announceSyncCompleted();

    window.removeEventListener(SYNC_COMPLETED_EVENT, listener);
    expect(locais).toHaveLength(1);
  });
});
