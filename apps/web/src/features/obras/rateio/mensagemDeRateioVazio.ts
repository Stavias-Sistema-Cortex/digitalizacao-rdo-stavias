import type { LeituraDeApontamentos } from "./apontamentosDoAparelho";

/**
 * A frase da tela sem linhas.
 *
 * <p>Vazio tem causas diferentes, e o gestor precisa saber qual é a dele: mês
 * sem RDO nenhum não é a mesma coisa que RDOs cujo conteúdo ainda não desceu,
 * nem que RDOs preenchidos sem mão de obra apontada. Uma frase só para os três
 * casos mandaria procurar defeito onde não há — ou faria passar por normal um
 * aparelho que está com metade do mês faltando.
 */
export function mensagemDeRateioVazio(
  leitura: LeituraDeApontamentos | null,
  carregando: boolean,
): string {
  if (carregando && !leitura) return "Lendo os RDOs do período…";
  if (!leitura || leitura.rdosLidos + leitura.rdosSemConteudo === 0) {
    return "Nenhum RDO neste período — o rateio aparece assim que o primeiro for lançado.";
  }
  if (leitura.rdosLidos === 0 && leitura.rdosSemConteudo > 0) {
    return `Os ${leitura.rdosSemConteudo} RDOs deste período ainda não desceram inteiros para este aparelho. Com rede, o rateio se completa sozinho.`;
  }
  if (
    leitura.rdosLidos > 0 &&
    leitura.rdosSemMaoDeObra === leitura.rdosLidos
  ) {
    return "Os RDOs deste período não têm mão de obra apontada — o rateio nasce do que o campo aponta.";
  }
  return "Nada corresponde ao filtro escolhido.";
}
