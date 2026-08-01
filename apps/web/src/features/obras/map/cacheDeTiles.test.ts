import { describe, expect, it, vi } from "vitest";

import { CACHES_DE_MAPA, descartarCacheDeMapa } from "./cacheDeTiles";

describe("descartarCacheDeMapa", () => {
  it("apaga exatamente os caches de mapa, e nenhum outro", () => {
    const deletados: string[] = [];
    const cacheStorage = {
      delete: vi.fn(async (nome: string) => {
        deletados.push(nome);
        return true;
      }),
    } as unknown as CacheStorage;

    return descartarCacheDeMapa(cacheStorage).then((total) => {
      expect(deletados).toEqual([...CACHES_DE_MAPA]);
      expect(total).toBe(CACHES_DE_MAPA.length);
    });
  });

  it("segue em frente quando um cache não pode ser apagado", async () => {
    // Falhar num cache não pode impedir os outros: a tentativa seguinte ainda
    // pode funcionar, e travar aqui deixaria o mapa preso na mesma falha.
    const cacheStorage = {
      delete: vi.fn(async (nome: string) => {
        if (nome === CACHES_DE_MAPA[0]) throw new Error("bloqueado");
        return true;
      }),
    } as unknown as CacheStorage;

    await expect(descartarCacheDeMapa(cacheStorage)).resolves.toBe(1);
  });

  it("não quebra em navegador sem Cache Storage", async () => {
    await expect(descartarCacheDeMapa(undefined)).resolves.toBe(0);
  });
});
