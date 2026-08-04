import { quilometroDeTexto } from "./trechoLocal";

/**
 * O que a pessoa preenche ao desenhar um trecho no mapa.
 *
 * <p>Este formulário já foi uma segunda porta de entrada: o trecho desenhado
 * gravava a própria quilometragem na geometria e convivia com o que o RDO
 * apurava, sem nada que reconciliasse os dois. Corrigir num lado deixava o
 * outro mentindo, e existia até uma função para comparar ambos e avisar quando
 * discordavam — administrar o sintoma em vez de resolver a causa.
 *
 * <p>Hoje o formulário continua igual para quem preenche, mas o que ele
 * produz vai para a linha de execução do RDO do dia (veja
 * {@code map/trechoAlimentaORdo.ts}). A geometria guarda só a forma. Uma
 * verdade, duas portas: quem prefere desenhar desenha, quem prefere digitar
 * digita, e o trecho lê sempre do RDO.
 */
export interface CadastroTrecho {
  rodovia: string;
  sentido: string;
  faixa: string;
  kmInicial: string;
  kmFinal: string;
  /** Extensão medida em campo. Vazio deixa valer a medida da própria linha. */
  extensaoM: string;
  status: StatusTrechoCadastrado;
}

export type StatusTrechoCadastrado =
  | "PENDENTE"
  | "EM_EXECUCAO"
  | "CONCLUIDO";

export const STATUS_DO_TRECHO: ReadonlyArray<{
  valor: StatusTrechoCadastrado;
  rotulo: string;
}> = [
  { valor: "PENDENTE", rotulo: "Pendente" },
  { valor: "EM_EXECUCAO", rotulo: "Em execução" },
  { valor: "CONCLUIDO", rotulo: "Concluído" },
];

/**
 * Faixa interditada, no vocabulário de quem sinaliza a pista.
 *
 * "Ambas" não é uma faixa: é a declaração de que as duas estão interditadas, e
 * vira dois blocos no esquemático em vez de um rótulo que ninguém sabe ler.
 */
export const FAIXAS_INTERDITAVEIS: ReadonlyArray<{
  valor: string;
  rotulo: string;
}> = [
  { valor: "DIREITA", rotulo: "Direita (lenta)" },
  { valor: "ESQUERDA", rotulo: "Esquerda (rápida)" },
  { valor: "AMBAS", rotulo: "Ambas as faixas" },
];

export const CADASTRO_VAZIO: CadastroTrecho = {
  rodovia: "",
  sentido: "",
  faixa: "",
  kmInicial: "",
  kmFinal: "",
  extensaoM: "",
  status: "PENDENTE",
};

function limpo(valor: string): string | null {
  const texto = valor.trim();
  return texto ? texto : null;
}

function numeroPositivo(valor: string): number | null {
  const texto = valor.trim().replace(",", ".");
  if (!texto) return null;
  const numero = Number(texto);
  return Number.isFinite(numero) && numero > 0 ? numero : null;
}

export function validarCadastro(cadastro: CadastroTrecho): string | null {
  if (!limpo(cadastro.rodovia)) {
    return "Informe a rodovia do trecho.";
  }
  const inicial = quilometroDeTexto(cadastro.kmInicial);
  const final = quilometroDeTexto(cadastro.kmFinal);
  if (inicial === null || final === null) {
    // Um extremo isolado é ponto, não trecho: sem os dois o bloco não tem onde
    // ser desenhado e o lançamento sairia invisível no esquemático.
    return "Informe o km inicial e o km final para o trecho ser posicionável.";
  }
  if (inicial === final) {
    return "O km inicial e o km final não podem ser o mesmo ponto.";
  }
  if (cadastro.extensaoM.trim() && numeroPositivo(cadastro.extensaoM) === null) {
    return "A extensão medida precisa ser um número de metros maior que zero.";
  }
  return null;
}

/**
 * Extensão medida em campo, quando declarada.
 *
 * <p>Zero e texto sem número não são medida: devolvem {@code null} para que a
 * própria linha desenhada valha, em vez de um comprimento inventado.
 */
export function extensaoMedidaEmCampo(cadastro: CadastroTrecho): number | null {
  return numeroPositivo(cadastro.extensaoM);
}

