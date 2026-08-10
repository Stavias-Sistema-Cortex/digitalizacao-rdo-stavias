import type {
  EquipamentoDraft,
  MaoObraDraft,
  MaterialDraft,
  RdoDraft,
  ServicoExecutadoDraft,
} from "./rdo.types";

/**
 * O rascunho de um RDO novo a partir de um RDO já preenchido.
 *
 * <p>Um dia de obra repete muita coisa do dia anterior — o turno, o trecho
 * programado, a frota que subiu, quem assina — e nada disso muda por virar a
 * data. Redigitar tudo todo dia é o trabalho que a clonagem apaga.
 *
 * <p>Ela traz a montagem inteira: a equipe, as frentes de serviço, a frota e
 * os insumos, com a identidade de cada linha preenchida. O que fica em branco
 * são os <em>números</em> — quantidades, medidas, horas, clima, fotos. Esses
 * são a resposta à pergunta "o que aconteceu hoje", e trazê-los preenchidos de
 * ontem transforma a pergunta em sugestão: o apontador confirma sem conferir, e
 * o relatório passa a somar produção que ninguém mediu. É o erro que a clonagem
 * existiria para evitar e seria o primeiro a causar.
 *
 * <p>A fronteira entre "repete" e "é do dia" é a única decisão desta função, e
 * ela está escrita campo a campo abaixo em vez de derivada por regra, porque
 * regra genérica erra no caso que importa.
 */

/**
 * Um equipamento repete a identidade, não a jornada.
 *
 * <p>A retroescavadeira é a mesma; as horas em que ela trabalhou não são. Zerar
 * hora e quantidade obriga a declarar o dia, que é o ponto.
 */
function equipamentoSemJornada(
  item: EquipamentoDraft,
  novoId: () => string,
): EquipamentoDraft {
  return {
    ...item,
    localId: novoId(),
    quantidade: "",
    horaInicio: "",
    horaFim: "",
    observacoes: "",
  };
}

/**
 * Um serviço repete o que é, não quanto foi feito hoje.
 *
 * <p>A linha inteira nascia vazia, e refazê-la custava escolher o serviço no
 * catálogo, o preço, a unidade, a pista, a faixa e o quilômetro — tudo igual
 * ao do dia anterior, redigitado. Era o maior trabalho de uma clonagem, e o
 * que menos muda de um dia para o outro numa frente que segue no mesmo trecho.
 *
 * <p>O que fica em branco é a resposta a "o que aconteceu hoje": quantidade,
 * largura e espessura. Trazê-las preenchidas de ontem transformaria a pergunta
 * em sugestão — o apontador confirma sem conferir, e o relatório passa a somar
 * produção que ninguém mediu. É o erro que a clonagem existe para evitar e
 * seria o primeiro a causar.
 *
 * <p>O status volta para REGISTRADA: validar é ato de outra pessoa sobre um
 * dia específico, e herdar a validação de ontem assinaria por ela.
 */
function servicoSemAProducaoDoDia(
  item: ServicoExecutadoDraft,
  novoId: () => string,
): ServicoExecutadoDraft {
  return {
    ...item,
    localId: novoId(),
    quantidadeExecutada: "",
    larguraM: "",
    espessuraCm: "",
    statusValidacao: "REGISTRADA",
    retrabalho: false,
    producaoRejeitada: false,
    observacoes: "",
  };
}

/**
 * A pessoa continua a mesma; o dia dela, não.
 *
 * <p>Era o pedido mais direto de quem usa: clonar tinha que trazer as mesmas
 * pessoas já colocadas da equipe. Elas vinham zeradas aqui e eram
 * reconstruídas depois a partir do RDO <em>anterior</em> — que numa clonagem
 * quase nunca é o RDO que se escolheu copiar. Quem clonava o RDO de segunda
 * para repetir a frente recebia a equipe de sexta.
 *
 * <p>A pessoa entra já selecionada, porque foi ela que a clonagem veio buscar.
 * Hora, percentual e observação nascem em branco: são o dia dela, não a
 * identidade dela.
 *
 * <p>A origem passa a ser o clone, e não o RDO anterior: {@code origemItemId}
 * apontaria para uma linha de outro documento, e o servidor a lê como herança
 * declarada. Dizer que veio de onde não veio quebraria a cadeia.
 */
function pessoaDaMesmaEquipe(
  item: MaoObraDraft,
  novoId: () => string,
): MaoObraDraft {
  return {
    ...item,
    localId: novoId(),
    origemItemId: "",
    sourceRdoId: "",
    origin: "MANUAL",
    availability: "AVAILABLE",
    selected: true,
    horaInicio: "",
    horaFim: "",
    observacoes: "",
  };
}

/**
 * Um material repete o que é, não o quanto.
 *
 * <p>Nome, unidade e fornecedor descrevem o insumo. Previsto, usinado, aplicado
 * e sobra descrevem o dia — e a nota fiscal pertence a uma entrega, não a duas.
 */
function materialSemQuantidade(
  item: MaterialDraft,
  novoId: () => string,
): MaterialDraft {
  return {
    ...item,
    localId: novoId(),
    quantidadePrevista: "",
    quantidadeUsinada: "",
    quantidadeAplicada: "",
    quantidadeSobra: "",
    notaFiscal: "",
    observacoes: "",
  };
}

/**
 * Monta o rascunho-base de um clone.
 *
 * <p>Devolve um `RdoDraft` para ser passado como `baseDraft` à criação. Vários
 * campos ficam como estão aqui e são sobrescritos depois por
 * `applyRdoCreationContext` — obra, data, número, RDO anterior, cliente,
 * contrato, rodovia, cidade, UF e apontador vêm todos do contexto versionado do
 * servidor, e é lá que devem vir. Preenchê-los aqui não teria efeito, e confiar
 * que teriam seria o engano fácil.
 *
 * <p>A mão de obra é a exceção, e foi onde a clonagem enganava. Ela também vinha
 * do contexto, reconstruída de `carryForwardWorkforce` a partir do RDO
 * <em>anterior</em> — que numa clonagem quase nunca é o RDO que se escolheu
 * copiar. Quem clonava o RDO de segunda para repetir a frente recebia a equipe
 * de sexta. Agora a equipe vem do clone, e `applyRdoCreationContext` respeita a
 * que já está no rascunho.
 */
export function rascunhoClonadoDe(
  origem: RdoDraft,
  novoId: () => string = () => crypto.randomUUID(),
): RdoDraft {
  return {
    ...origem,
    id: novoId(),

    // A data NÃO se repete: o recurso se chama "clonar para outra data", e
    // herdá-la faria o diálogo pré-selecionar exatamente o dia do qual se está
    // copiando. Em branco, a tela cai no dia de hoje, que é a única sugestão
    // defensável. Isto ficou mais grave depois que um segundo RDO no mesmo dia
    // passou a ser aceito: sem zerar, um clone distraído vira duplicata do
    // próprio dia de origem, e nada no banco o impede.
    dataRdo: "",

    // Quem preenche é quem está com a sessão aberta, não quem preencheu o RDO
    // copiado. `comPreenchidoPor` só preenche campo vazio, então herdar aqui
    // faria o RDO novo alegar autoria de outra pessoa — a mesma falsidade de
    // procedência que se evitou ao separar clone de importação.
    preenchidoPor: "",

    // Identidade: zerada por higiene. O contexto sobrescreve tudo isto, e
    // deixar valor velho aqui só sobreviveria se o contexto falhasse — momento
    // em que herdar o número de outro RDO seria o pior desfecho possível.
    numeroRdo: "",
    previousRdoId: "",
    previousRdoNumber: "",
    creationContextVersion: null,
    programacaoId: "",

    // O dia: tudo o que responde "o que aconteceu hoje" nasce vazio.
    controlesGeometricos: [],
    attachments: [],
    condicaoManha: "",
    condicaoTarde: "",
    condicaoNoite: "",
    condicaoTrabalho: "",
    pluviometriaMm: "",
    observacoes: "",

    // A evidência de importação pertence ao documento que a originou. Um clone
    // não foi importado de lugar nenhum, e dizer que foi falsificaria a origem.
    importEvidence: null,

    // O que se repete: a equipe, as frentes de serviço, a frota e os
    // insumos — tudo sem os números do dia.
    maoObra: origem.maoObra.map((item) => pessoaDaMesmaEquipe(item, novoId)),
    alocacoesColaboradores: origem.alocacoesColaboradores.map((item) => ({
      ...item,
      localId: novoId(),
      horaInicio: "",
      horaFim: "",
      percentualDia: "" as const,
      observacoes: "",
    })),
    servicosExecutados: origem.servicosExecutados.map((item) =>
      servicoSemAProducaoDoDia(item, novoId),
    ),
    equipamentos: origem.equipamentos.map((item) =>
      equipamentoSemJornada(item, novoId),
    ),
    materiais: origem.materiais.map((item) =>
      materialSemQuantidade(item, novoId),
    ),

    syncStatus: "LOCAL_ONLY",
  };
}

/**
 * O que o clone traz, em texto, para a tela poder avisar antes de criar.
 *
 * <p>Clonar sem dizer o que foi copiado é o mesmo problema que copiar produção:
 * o apontador não sabe o que precisa conferir.
 */
export function resumoDoQueOCloneTraz(origem: RdoDraft): string {
  const partes: string[] = [];
  if (origem.turno || origem.horaInicio || origem.horaFim) {
    partes.push("turno e horário");
  }
  if (
    origem.kmInicialProgramado ||
    origem.kmFinalProgramado ||
    origem.kmInicialInterditado ||
    origem.kmFinalInterditado
  ) {
    partes.push("trecho programado");
  }
  const pessoas = origem.maoObra.filter((item) => item.selected).length;
  if (pessoas > 0) {
    partes.push(pessoas === 1 ? "1 pessoa da equipe" : `${pessoas} pessoas da equipe`);
  }
  if (origem.servicosExecutados.length > 0) {
    partes.push(
      origem.servicosExecutados.length === 1
        ? "1 frente de serviço"
        : `${origem.servicosExecutados.length} frentes de serviço`,
    );
  }
  if (origem.equipamentos.length > 0) {
    partes.push(
      origem.equipamentos.length === 1
        ? "1 equipamento"
        : `${origem.equipamentos.length} equipamentos`,
    );
  }
  if (origem.materiais.length > 0) {
    partes.push(
      origem.materiais.length === 1
        ? "1 material"
        : `${origem.materiais.length} materiais`,
    );
  }
  if (origem.encarregadoObra || origem.fiscalizacaoCampo) {
    partes.push("responsáveis");
  }

  if (partes.length === 0) {
    return "Este RDO não tem nada que valha copiar.";
  }
  return `Traz ${partes.join(", ")}. As quantidades, as medidas, as horas e o clima ficam em branco.`;
}
