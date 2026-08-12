/**
 * A cor de cada obra na matriz do rateio.
 *
 * <p>A tela é lida a olho: trinta e uma colunas de dias, uma cor por obra, e o
 * padrão que se enxerga de longe é justamente o que interessa — quem trocou de
 * frente no meio do mês, que semana a obra parou, quem se dividiu entre duas.
 * Para isso a cor precisa de duas propriedades.
 *
 * <p><b>Estável</b>: a mesma obra tem a mesma cor em junho e em julho, no
 * celular e no computador. Distribuir por ordem de aparição faria a cor mudar
 * quando uma obra nova entrasse no período — e a leitura do mês passado, de
 * memória, passaria a enganar. Por isso a posição inicial sai de um resumo do
 * identificador da obra, que não muda nunca.
 *
 * <p><b>Distinta</b>: duas obras do mesmo retrato não podem cair na mesma cor,
 * ou a matriz mente. Como o resumo pode repetir, a colisão é resolvida
 * empurrando para a próxima cor livre, na ordem do identificador — o que
 * mantém o resultado o mesmo em qualquer aparelho.
 */

/** Quantas cores a paleta tem; a definição visual está no CSS. */
export const CORES_DO_RATEIO = 10;

/** Resumo estável de um texto: mesma entrada, mesmo número, sempre. */
function resumo(texto: string): number {
  let valor = 0;
  for (let indice = 0; indice < texto.length; indice += 1) {
    valor = (valor * 31 + texto.charCodeAt(indice)) % 1_000_003;
  }
  return valor;
}

/**
 * Dá a cada obra um número de cor entre 1 e {@link CORES_DO_RATEIO}.
 *
 * <p>Passando de dez obras no mesmo período, as cores voltam a se repetir —
 * inevitável, e melhor do que inventar tons que ninguém distingue. A tela
 * continua nomeando a obra na legenda e no balão de cada célula, que é onde a
 * dúvida se resolve.
 */
export function coresPorObra(
  obraIds: readonly string[],
): ReadonlyMap<string, number> {
  const cores = new Map<string, number>();
  const ocupadas = new Set<number>();
  const ordenadas = [...new Set(obraIds)].sort();

  for (const obraId of ordenadas) {
    const inicio = resumo(obraId) % CORES_DO_RATEIO;
    let escolhida = inicio;
    for (let passo = 0; passo < CORES_DO_RATEIO; passo += 1) {
      const candidata = (inicio + passo) % CORES_DO_RATEIO;
      if (!ocupadas.has(candidata)) {
        escolhida = candidata;
        break;
      }
    }
    ocupadas.add(escolhida);
    cores.set(obraId, escolhida + 1);
  }

  return cores;
}
