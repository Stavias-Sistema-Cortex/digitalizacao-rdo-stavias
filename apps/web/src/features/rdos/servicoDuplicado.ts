import type { ServicoExecutadoDraft } from "./rdo.types";

/**
 * Uma segunda frente sobre o mesmo trecho, sem redigitar o trecho.
 *
 * <p>Numa caixa de rodovia raramente acontece um serviço só: a fresagem, a
 * imprimação e o CBUQ caem no mesmo quilômetro, na mesma pista, na mesma faixa,
 * com a mesma largura. Lançar os três significava preencher três vezes os
 * mesmos seis campos — e cada digitação é uma chance de o segundo lançamento
 * dizer km 12,400 onde o primeiro disse 12,300, divergência que ninguém
 * percebe até a medição não fechar.
 *
 * <p>A cópia traz o <em>lugar</em> — quilômetro inicial e final, pista, faixa,
 * largura, espessura — e devolve em branco o <em>que foi feito ali</em>. Essa
 * fronteira não é simetria estética: é a razão de existir do recurso. Copiar
 * também o serviço economizaria um campo e criaria a possibilidade de duas
 * linhas idênticas subirem por descuido, cada uma somando a mesma produção na
 * medição. Em branco, a linha nova cai no aviso que já existe — "selecione no
 * catálogo um serviço com unidade válida" — e não sincroniza até que alguém
 * diga o que ela é. O único campo que sobra para preencher é exatamente o que
 * se queria trocar.
 *
 * <p>A quantidade sai junto com a unidade porque é dela que depende: sem saber
 * se o contrato mede em metro, metro quadrado ou metro cúbico, não há número a
 * afirmar. Assim que o serviço é escolhido, a tela a recalcula sozinha a partir
 * do trecho, da largura e da espessura que a cópia já trouxe.
 *
 * <p>O status volta ao início e as marcas de retrabalho e produção rejeitada
 * caem: são juízos sobre um serviço específico, não sobre o pedaço de estrada.
 * Herdá-los faria o CBUQ nascer reprovado porque a fresagem foi.
 */
export function servicoDuplicadoDe(
  item: ServicoExecutadoDraft,
  novoId: () => string = () => crypto.randomUUID(),
): ServicoExecutadoDraft {
  return {
    ...item,
    localId: novoId(),

    // O que a linha nova precisa declarar por conta própria.
    serviceId: "",
    priceVersionId: "",
    servicoNome: "",
    itemContratualId: "",
    unidade: "",
    quantidadeExecutada: "",

    // O julgamento do dia sobre o serviço copiado não vale para o novo.
    statusValidacao: "",
    retrabalho: false,
    producaoRejeitada: false,
    observacoes: "",
  };
}

/**
 * Insere a cópia logo abaixo do original, e não no fim da lista.
 *
 * <p>As duas linhas descrevem o mesmo trecho e só se leem juntas: separadas por
 * outras frentes, a relação some e conferir uma contra a outra vira rolagem.
 *
 * <p>Sem o item pedido, devolve a mesma lista — a mesma referência, para que o
 * estado do formulário não seja trocado por um clone igual e a tela não repinte
 * a lista inteira à toa.
 */
export function duplicarServicoExecutado(
  servicos: ServicoExecutadoDraft[],
  localId: string,
  novoId: () => string = () => crypto.randomUUID(),
): ServicoExecutadoDraft[] {
  const posicao = servicos.findIndex((item) => item.localId === localId);
  if (posicao < 0) return servicos;
  return [
    ...servicos.slice(0, posicao + 1),
    servicoDuplicadoDe(servicos[posicao], novoId),
    ...servicos.slice(posicao + 1),
  ];
}
