import {
  extensaoMedidaEmCampo,
  type CadastroTrecho,
} from "../trecho/trechoCadastrado";
import type { ServicoExecutadoDraft } from "../../rdos/rdo.types";

/**
 * O desenho vira apontamento.
 *
 * <p>Desenhar no mapa e lançar no RDO descreviam o mesmo trabalho por caminhos
 * separados, e a separação era o defeito: o quilômetro existia em dois lugares
 * — nas propriedades da geometria e na linha de execução do RDO — sem nada que
 * os reconciliasse. Havia até uma função para comparar os dois e avisar quando
 * discordavam, o que administra o sintoma em vez de resolver a causa.
 *
 * <p>Aqui o desenho deixa de guardar quilômetro próprio e passa a escrever a
 * linha de execução do apontamento do dia. Uma verdade só, duas portas: quem
 * prefere desenhar desenha, quem prefere digitar digita, e o trecho lê sempre
 * do RDO.
 */

/** Nome do serviço quando o cadastro do desenho não nomeia um. */
const SERVICO_SEM_NOME = "Trecho desenhado no mapa";

function limpo(valor: string): string {
  return valor.trim();
}

/**
 * A linha de execução que este desenho declara.
 *
 * <p>Só os campos que o desenho realmente afirma são preenchidos. Serviço,
 * item contratual e quantidade ficam vazios de propósito: quem desenha marca
 * onde o trabalho aconteceu, não quanto foi medido nem contra qual item ele
 * será faturado. Inventar esses valores aqui os faria descer para o Financeiro
 * como se alguém os tivesse declarado.
 */
export function execucaoDoTrechoDesenhado(input: {
  cadastro: CadastroTrecho;
  localId: string;
  /** Nome do serviço, quando a tela souber qual é. */
  servicoNome?: string;
}): ServicoExecutadoDraft {
  const { cadastro } = input;
  return {
    localId: input.localId,
    serviceId: "",
    priceVersionId: "",
    servicoNome: limpo(input.servicoNome ?? "") || SERVICO_SEM_NOME,
    itemContratualId: "",
    quantidadeExecutada: "",
    unidade: "",
    // O quilômetro passa a morar aqui, e só aqui. Era isto que estava
    // duplicado na geometria e que ninguém conseguia corrigir num lugar só.
    trechoInicial: limpo(cadastro.kmInicial),
    trechoFinal: limpo(cadastro.kmFinal),
    pista: "",
    faixa: limpo(cadastro.faixa),
    localizacao: limpo(cadastro.rodovia),
    turno: "",
    // Registrada, não validada: o desenho é declaração de quem estava lá, e
    // validar é ato de outra pessoa. Nascer validado apagaria essa distinção.
    statusValidacao: "REGISTRADA",
    retrabalho: false,
    producaoRejeitada: false,
    observacoes: limpo(cadastro.sentido)
      ? `Sentido ${limpo(cadastro.sentido)}.`
      : "",
  };
}

/**
 * As propriedades que sobram para a geometria depois que o RDO assume o
 * quilômetro.
 *
 * <p>A geometria guarda a forma desenhada e o que descreve a própria linha —
 * nada que o apontamento já afirme. Deixar {@code kmInicial} e {@code kmFinal}
 * aqui recriaria a segunda declaração que este trabalho existe para eliminar:
 * corrigir o RDO deixaria a geometria mentindo, e ninguém saberia qual das
 * duas ler.
 */
export function propriedadesDaFormaDesenhada(
  cadastro: CadastroTrecho,
  extensaoDaLinhaM: number | null,
): Record<string, unknown> {
  const propriedades: Record<string, unknown> = {
    status: cadastro.status,
  };
  const rodovia = limpo(cadastro.rodovia);
  if (rodovia) propriedades.rodovia = rodovia;
  const sentido = limpo(cadastro.sentido);
  if (sentido) propriedades.sentido = sentido;
  const faixa = limpo(cadastro.faixa);
  if (faixa) propriedades.faixa = faixa;

  // Nome é rótulo do balão no mapa, e por isso não repete o quilômetro: se
  // repetisse, corrigir o RDO deixaria a linha rotulada com o km antigo — a
  // mesma divergência, escrita em prosa. Rodovia e sentido saem dos campos
  // aqui do lado, então o rótulo não tem como discordar deles.
  const nome = [rodovia, sentido && `sentido ${sentido}`]
    .filter(Boolean)
    .join(" · ");
  if (nome) propriedades.nome = nome;

  // A extensão digitada é medida de campo e prevalece; sem ela vale o
  // comprimento da própria linha desenhada, que é geometria, não estimativa.
  // Nenhuma das duas é declaração de quantidade — essa mora no RDO.
  const medida = extensaoMedidaEmCampo(cadastro) ?? extensaoDaLinhaM;
  if (medida !== null && Number.isFinite(medida) && medida > 0) {
    propriedades.extensaoM = Math.round(medida);
  }
  return propriedades;
}
