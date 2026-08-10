/**
 * A quantidade executada deixa de ser digitada e passa a ser medida.
 *
 * <p>Ela era um campo ao lado do trecho, da largura e da espessura — as três
 * parcelas de que ela é feita. Quem apontava fresagem de 800 m² digitava o
 * km inicial, o km final, a largura e depois "800", e nada no formulário
 * conferia se os dois relatos combinavam. Quando não combinavam, e não
 * combinavam sempre, o Financeiro media por um número e a obra por outro.
 *
 * <p>Agora a unidade do serviço decide qual das medidas é a quantidade:
 * comprimento para o que se mede em metro, área para metro quadrado, volume
 * para metro cúbico. A unidade não vem mais do apontador — vem do catálogo,
 * que é onde o contrato diz em que se mede cada serviço.
 *
 * <p>Unidade que não é geométrica — tonelada, hora, unidade, verba — não tem
 * medida a derivar, e devolver zero ali seria afirmar que nada foi executado.
 * Devolve nulo, que é o que "não sei" quer dizer.
 */

import { medidasDoServico } from "./rdoCalculations";
import type { NumericInput } from "./rdo.types";

export interface ServicoMedivel {
  trechoInicial: string;
  trechoFinal: string;
  larguraM: NumericInput;
  espessuraM: NumericInput;
  unidade: string;
}

/** A medida que a unidade nomeia, sem depender de como ela foi escrita. */
type Dimensao = "COMPRIMENTO" | "AREA" | "VOLUME" | "NENHUMA";

/**
 * Lê a unidade sem exigir que ela tenha sido escrita de um jeito só.
 *
 * <p>"M2", "m²", "M 2" e "metro quadrado" são a mesma unidade para quem
 * digita, e o catálogo já aceita todas. A quantidade não pode depender de
 * qual delas foi gravada.
 */
export function dimensaoDaUnidade(unidade: string): Dimensao {
  const chave = unidade
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[\s.^]/g, "");
  if (!chave) return "NENHUMA";
  if (["m3", "m³", "metrocubico", "metroscubicos"].includes(chave)) {
    return "VOLUME";
  }
  if (["m2", "m²", "metroquadrado", "metrosquadrados"].includes(chave)) {
    return "AREA";
  }
  if (["m", "ml", "metro", "metros", "metrolinear", "metroslineares"]
    .includes(chave)) {
    return "COMPRIMENTO";
  }
  return "NENHUMA";
}

/**
 * A quantidade que as medidas do serviço afirmam, ou nulo quando não afirmam
 * nenhuma.
 *
 * <p>Nulo é ausência de medida e nunca zero: zero é uma afirmação de que nada
 * foi executado, e o RDO que sobe zero produção fecha o mês a menos sem que
 * ninguém saiba por quê.
 */
export function quantidadeDoServico(item: ServicoMedivel): number | null {
  const { comprimentoM, areaM2, volumeM3 } = medidasDoServico(item);
  switch (dimensaoDaUnidade(item.unidade)) {
    case "COMPRIMENTO":
      return comprimentoM;
    case "AREA":
      return areaM2;
    case "VOLUME":
      return volumeM3;
    default:
      return null;
  }
}

/**
 * Reescreve a quantidade do serviço a partir das medidas.
 *
 * <p>Devolve o mesmo objeto quando nada muda, para que o React não repinte a
 * lista inteira a cada tecla digitada em outro campo.
 */
export function comQuantidadeMedida<T extends ServicoMedivel & {
  quantidadeExecutada: NumericInput;
}>(item: T): T {
  const medida = quantidadeDoServico(item);
  const proxima: NumericInput = medida === null ? "" : medida;
  return item.quantidadeExecutada === proxima
    ? item
    : { ...item, quantidadeExecutada: proxima };
}

/** O rótulo da medida que vale como quantidade, para dizê-lo na tela. */
export function rotuloDaQuantidade(unidade: string): string | null {
  switch (dimensaoDaUnidade(unidade)) {
    case "COMPRIMENTO":
      return "Comprimento";
    case "AREA":
      return "Área";
    case "VOLUME":
      return "Volume";
    default:
      return null;
  }
}
