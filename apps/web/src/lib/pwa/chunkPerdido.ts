/**
 * O chunk que o deploy deixou órfão.
 *
 * <p>O aplicativo carrega telas pesadas sob demanda — a exportação de PDF e
 * XLSX do RDO é o caso maior — e cada pedaço é pedido pelo nome com hash da
 * build que estava aberta. Depois de um deploy, esse arquivo não existe mais
 * no servidor: quem ficou com o app aberto clica em "Exportar" e o pedaço
 * nunca chega. O botão simplesmente morre, sem uma palavra, até alguém
 * recarregar por conta própria — e ninguém descobre sozinho que a cura é F5.
 *
 * <p>A saída é recarregar pela pessoa: o `vite:preloadError` diz exatamente
 * isso ("pedi um pedaço da build antiga e ele já não existe"), e a página
 * recarregada nasce na build nova, onde todos os pedaços existem. O guarda de
 * tempo evita o pior caso — um erro que sobrevive à recarga viraria um laço
 * infinito de reloads, e aí nem a tela de erro a pessoa conseguiria ler.
 */

const CHAVE_DA_ULTIMA_RECARGA = "cortex.pwa.recarga-por-chunk-perdido";

/** Janela em que uma segunda falha NÃO recarrega de novo (evita o laço). */
export const JANELA_ANTI_LACO_MS = 60_000;

/**
 * Decide se a falha de chunk deve recarregar a página.
 *
 * <p>Pura para ser testável: recebe o instante atual e o da última recarga
 * provocada por chunk perdido (nulo quando nunca houve).
 */
export function deveRecarregarPorChunkPerdido(
  agoraMs: number,
  ultimaRecargaMs: number | null,
): boolean {
  if (ultimaRecargaMs === null) return true;
  return agoraMs - ultimaRecargaMs > JANELA_ANTI_LACO_MS;
}

function lerUltimaRecarga(storage: Storage): number | null {
  const bruto = storage.getItem(CHAVE_DA_ULTIMA_RECARGA);
  if (!bruto) return null;
  const valor = Number(bruto);
  return Number.isFinite(valor) ? valor : null;
}

/**
 * Arma o tratamento global: primeiro `vite:preloadError` recarrega a página;
 * um segundo dentro da janela deixa o erro seguir, para a tela poder falhar
 * com mensagem em vez de piscar para sempre.
 */
export function armarRecargaPorChunkPerdido(
  alvo: Pick<Window, "addEventListener"> = window,
  storage: Storage = window.sessionStorage,
  recarregar: () => void = () => window.location.reload(),
  agora: () => number = () => Date.now(),
): void {
  alvo.addEventListener("vite:preloadError", (evento) => {
    const instante = agora();
    if (!deveRecarregarPorChunkPerdido(instante, lerUltimaRecarga(storage))) {
      return;
    }
    // Recarregar É o tratamento: impede o Vite de propagar o erro que a
    // recarga vai resolver.
    evento.preventDefault();
    try {
      storage.setItem(CHAVE_DA_ULTIMA_RECARGA, String(instante));
    } catch {
      // Sem storage (modo privado esgotado), ainda vale recarregar uma vez —
      // o guarda só perde a memória entre recargas.
    }
    recarregar();
  });
}
