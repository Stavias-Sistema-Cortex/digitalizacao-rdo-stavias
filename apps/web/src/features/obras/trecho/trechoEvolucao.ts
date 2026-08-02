import type { DiaExecutado } from "./trechoGeometry";

/**
 * Largura de cada dia no trilho de rolagem, em pixels.
 *
 * É constante de propósito: a conversão rolagem → dia é uma divisão exata,
 * verificável sem medir layout — o que também a torna estável em qualquer
 * densidade de tela.
 */
export const LARGURA_DO_DIA_PX = 76;

/**
 * Converte a posição de rolagem do trilho no índice do dia sob a agulha.
 *
 * O trilho tem uma célula por dia executado e folga de meia janela nas duas
 * pontas, então `scrollLeft` zero deixa o primeiro dia sob a agulha central e
 * a rolagem máxima deixa o último. Qualquer posição intermediária cai no dia
 * mais próximo; fora da faixa, prende nas pontas em vez de inventar dia.
 */
export function indiceDoDiaNaRolagem(
  totalDeDias: number,
  scrollLeft: number,
): number {
  if (totalDeDias <= 0) {
    return -1;
  }
  const indice = Math.round(scrollLeft / LARGURA_DO_DIA_PX);
  return Math.min(Math.max(indice, 0), totalDeDias - 1);
}

/** Posição de rolagem que centraliza o dia pedido sob a agulha. */
export function rolagemDoIndice(indice: number): number {
  return Math.max(indice, 0) * LARGURA_DO_DIA_PX;
}

export interface AcumuladoDaEvolucao {
  dias: number;
  totalRdos: number;
  extensaoM: number;
  massaTonelada: number;
}

/**
 * O que a obra tinha produzido até o fim do dia dado, inclusive.
 *
 * É a leitura que acompanha a agulha: enquanto o esquemático mostra o desenho
 * acumulado, este resumo diz quanto aquilo mede. Soma apenas o que os dias
 * registraram — dia sem produção não existe na lista e não vira zero no meio.
 */
export function acumuladoAteODia(
  dias: readonly DiaExecutado[],
  dia: string,
): AcumuladoDaEvolucao {
  const ateODia = dias.filter((registro) => registro.data <= dia);
  return {
    dias: ateODia.length,
    totalRdos: ateODia.reduce((total, registro) => total + registro.totalRdos, 0),
    extensaoM: ateODia.reduce(
      (total, registro) => total + registro.extensaoM,
      0,
    ),
    massaTonelada: ateODia.reduce(
      (total, registro) => total + registro.massaTonelada,
      0,
    ),
  };
}
