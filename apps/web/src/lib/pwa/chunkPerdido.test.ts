// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";

import {
  JANELA_ANTI_LACO_MS,
  armarRecargaPorChunkPerdido,
  deveRecarregarPorChunkPerdido,
} from "./chunkPerdido";

/*
 * O caso real que motivou isto: quatro deploys no mesmo dia, o aplicativo
 * aberto desde antes do primeiro, e o botão de exportar PDF do RDO morto —
 * o chunk da exportação era pedido pelo hash da build antiga, que o servidor
 * já não tinha. Sem este tratamento, a "cura" era saber, por conta própria,
 * que F5 resolve.
 */
describe("a recarga por chunk perdido", () => {
  it("a primeira falha recarrega", () => {
    expect(deveRecarregarPorChunkPerdido(1_000, null)).toBe(true);
  });

  it("uma segunda falha logo após a recarga não entra em laço", () => {
    expect(
      deveRecarregarPorChunkPerdido(2_000, 1_000),
    ).toBe(false);
    expect(
      deveRecarregarPorChunkPerdido(1_000 + JANELA_ANTI_LACO_MS + 1, 1_000),
    ).toBe(true);
  });

  it("recarrega no evento e guarda o instante para o guarda", () => {
    const listeners = new Map<string, (evento: Event) => void>();
    const alvo = {
      addEventListener: (tipo: string, ouvinte: EventListener) => {
        listeners.set(tipo, ouvinte);
      },
    } as unknown as Pick<Window, "addEventListener">;
    const memoria = new Map<string, string>();
    const storage = {
      getItem: (chave: string) => memoria.get(chave) ?? null,
      setItem: (chave: string, valor: string) => {
        memoria.set(chave, valor);
      },
    } as unknown as Storage;
    const recarregar = vi.fn();

    armarRecargaPorChunkPerdido(alvo, storage, recarregar, () => 5_000);
    const evento = new Event("vite:preloadError", { cancelable: true });
    listeners.get("vite:preloadError")?.(evento);

    expect(recarregar).toHaveBeenCalledTimes(1);
    expect(evento.defaultPrevented).toBe(true);

    // Segunda falha imediata: o erro segue para a tela, sem novo reload.
    const segundo = new Event("vite:preloadError", { cancelable: true });
    listeners.get("vite:preloadError")?.(segundo);
    expect(recarregar).toHaveBeenCalledTimes(1);
    expect(segundo.defaultPrevented).toBe(false);
  });
});
