/**
 * O que a tela vazia diz quando não há obra nenhuma.
 *
 * <p>Havia uma frase só — "conecte-se uma vez" — e ela mentia no caso mais
 * caro de diagnosticar: a conta nova cujo servidor respondeu, com sucesso, que
 * o escopo é vazio. A pessoa estava conectada, a sincronização estava verde, e
 * a tela mandava conectar. Beta sem vínculo com obra nenhuma é um estado
 * legítimo do cadastro — e é justamente o que a frase precisa dizer, porque a
 * solução não está no aparelho: está na Gestão de Obras, na mão de um Alfa.
 *
 * <p>As três frases separam as três situações que se vestiam de uma:
 * servidor confirmou escopo vazio (falta vínculo), servidor confirmou vazio
 * para um Alfa (não deveria acontecer — é pedido de socorro, não instrução), e
 * servidor ainda não respondido (aí sim, é esperar a rede).
 */
export function fraseDaHomeSemObras(
  hidratacaoConfirmada: boolean,
  alfa: boolean,
): string {
  if (!hidratacaoConfirmada) {
    return "O servidor ainda não respondeu neste aparelho. As obras chegam na primeira sincronização com rede.";
  }
  if (alfa) {
    return "O servidor respondeu, mas não devolveu nenhuma obra para o seu perfil Alfa. Recarregue os dados; se persistir, isto é um defeito — reporte.";
  }
  return "Seu usuário ainda não está vinculado a nenhuma obra. Peça a um Alfa para vincular você — ou alocar a sua equipe — a uma obra na Gestão de Obras; os dados aparecem na sincronização seguinte.";
}
