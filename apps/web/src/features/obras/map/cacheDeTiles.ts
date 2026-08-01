/**
 * Descarte dos caches de mapa do service worker.
 *
 * Os tiles são guardados com `CacheFirst`, que nunca revalida: uma entrada
 * gravada quebrada — uma resposta opaca, um corte de rede no meio do download —
 * fica servida para sempre e o painel vetorial sobe em branco, sem erro, porque
 * do ponto de vista do renderizador o arquivo chegou.
 *
 * Recarregar a página não resolve, já que o cache responde antes da rede. Este
 * é o botão de saída: apaga o que foi guardado e deixa a próxima tentativa ir
 * buscar de novo. Não apaga dado do usuário — tile é conteúdo público que se
 * baixa outra vez.
 */
export const CACHES_DE_MAPA = [
  "cortex-map-vetorial",
  "cortex-map-tiles",
] as const;

export async function descartarCacheDeMapa(
  cacheStorage: CacheStorage | undefined = typeof caches === "undefined"
    ? undefined
    : caches,
): Promise<number> {
  if (!cacheStorage) {
    // Navegador sem Cache Storage nunca guardou nada; não há o que descartar e
    // a tentativa seguinte já vai direto à rede.
    return 0;
  }
  const descartados = await Promise.all(
    CACHES_DE_MAPA.map(async (nome) => {
      try {
        return await cacheStorage.delete(nome);
      } catch {
        // Um cache que não pôde ser apagado não impede os outros, e a
        // tentativa seguinte ainda pode funcionar.
        return false;
      }
    }),
  );
  return descartados.filter(Boolean).length;
}
