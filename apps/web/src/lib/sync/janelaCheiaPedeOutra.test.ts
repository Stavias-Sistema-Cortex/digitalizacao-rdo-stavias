import { describe, expect, it, vi } from "vitest";

import { createAutomaticSyncScheduler } from "./automaticSyncScheduler";
import { aindaFaltaPuxar } from "./useAutomaticSync";

async function flush(): Promise<void> {
  for (let volta = 0; volta < 8; volta += 1) {
    await Promise.resolve();
  }
}

describe("aindaFaltaPuxar", () => {
  it("reconhece a janela que terminou cheia", () => {
    expect(aindaFaltaPuxar({ pulled: 5000, pullPendente: true })).toBe(true);
  });

  it("não confunde a janela em dia com uma janela cheia", () => {
    expect(aindaFaltaPuxar({ pulled: 12, pullPendente: false })).toBe(false);
  });

  /*
   * O agendador não conhece o motor e entrega o resumo como `unknown`. Nulo,
   * texto ou resumo antigo sem o campo não pedem outra janela — pedir por
   * engano poria o aparelho num laço de sincronização sem fim.
   */
  it("não pede outra janela quando não há resumo para ler", () => {
    expect(aindaFaltaPuxar(null)).toBe(false);
    expect(aindaFaltaPuxar("concluído")).toBe(false);
    expect(aindaFaltaPuxar({ pulled: 3 })).toBe(false);
  });
});

describe("o agendador diante de uma janela cheia", () => {
  /**
   * O aparelho recém-chegado atravessa o histórico em janelas cheias. Sem o
   * pedido imediato ele esperaria o intervalo entre cada uma, e a primeira
   * carga — que é justamente quando a pessoa está olhando para a tela vazia —
   * levaria dezenas de minutos.
   */
  it("roda outra janela em seguida quando a anterior encheu", async () => {
    const target = new EventTarget();
    const resumos = [
      { pulled: 5000, pullPendente: true },
      { pulled: 5000, pullPendente: true },
      { pulled: 40, pullPendente: false },
    ];
    let janela = 0;
    const syncNow = vi.fn(async () => resumos[Math.min(janela++, 2)]);
    const scheduler = createAutomaticSyncScheduler({
      syncNow,
      hasOnlineSession: () => true,
      isOnline: () => true,
      eventTarget: target,
      visibilityTarget: target,
      loadNextRetryAt: async () => null,
      onSuccess: (_trigger, resumo) => {
        if (aindaFaltaPuxar(resumo)) {
          scheduler.request("PULL_PENDENTE");
        }
      },
    });

    scheduler.start();
    await flush();

    // Três janelas: as duas cheias pediram a seguinte, e a terceira, em dia,
    // parou a corrente.
    expect(syncNow).toHaveBeenCalledTimes(3);
    scheduler.dispose();
  });

  it("para de pedir assim que o aparelho alcança o servidor", async () => {
    const target = new EventTarget();
    const syncNow = vi.fn(async () => ({ pulled: 7, pullPendente: false }));
    const scheduler = createAutomaticSyncScheduler({
      syncNow,
      hasOnlineSession: () => true,
      isOnline: () => true,
      eventTarget: target,
      visibilityTarget: target,
      loadNextRetryAt: async () => null,
      onSuccess: (_trigger, resumo) => {
        if (aindaFaltaPuxar(resumo)) {
          scheduler.request("PULL_PENDENTE");
        }
      },
    });

    scheduler.start();
    await flush();

    expect(syncNow).toHaveBeenCalledTimes(1);
    scheduler.dispose();
  });
});
