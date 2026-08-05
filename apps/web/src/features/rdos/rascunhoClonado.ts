import type {
  EquipamentoDraft,
  MaterialDraft,
  RdoDraft,
} from "./rdo.types";

/**
 * O rascunho de um RDO novo a partir de um RDO já preenchido.
 *
 * <p>Um dia de obra repete muita coisa do dia anterior — o turno, o trecho
 * programado, a frota que subiu, quem assina — e nada disso muda por virar a
 * data. Redigitar tudo todo dia é o trabalho que a clonagem apaga.
 *
 * <p>O que ela deliberadamente <em>não</em> traz é a produção: serviços
 * executados, quantidades aplicadas, medidas, clima, fotos. Esses números são a
 * resposta à pergunta "o que aconteceu hoje", e trazê-los preenchidos de ontem
 * transforma a pergunta em sugestão — o apontador confirma sem conferir, e o
 * relatório passa a somar produção que ninguém mediu. É o erro que a clonagem
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
 * contrato, rodovia, cidade, UF, mão de obra e apontador vêm todos do contexto
 * versionado do servidor, e é lá que devem vir. Preenchê-los aqui não teria
 * efeito, e confiar que teriam seria o engano fácil.
 *
 * <p>A mão de obra é o exemplo que mais engana: o clone não a copia porque
 * `applyRdoCreationContext` a reconstrói de `carryForwardWorkforce`, a partir do
 * RDO anterior canônico e do catálogo autorizado. Copiar aqui seria trabalho
 * jogado fora — e pior, daria a impressão de que a equipe do clone veio do RDO
 * escolhido quando ela vem de outro lugar.
 */
export function rascunhoClonadoDe(
  origem: RdoDraft,
  novoId: () => string = () => crypto.randomUUID(),
): RdoDraft {
  return {
    ...origem,
    id: novoId(),

    // Identidade: zerada por higiene. O contexto sobrescreve tudo isto, e
    // deixar valor velho aqui só sobreviveria se o contexto falhasse — momento
    // em que herdar o número de outro RDO seria o pior desfecho possível.
    numeroRdo: "",
    previousRdoId: "",
    previousRdoNumber: "",
    creationContextVersion: null,
    programacaoId: "",

    // O dia: tudo o que responde "o que aconteceu hoje" nasce vazio.
    servicosExecutados: [],
    alocacoesColaboradores: [],
    controlesGeometricos: [],
    attachments: [],
    maoObra: [],
    condicaoManha: "",
    condicaoTarde: "",
    condicaoNoite: "",
    condicaoTrabalho: "",
    pluviometriaMm: "",
    observacoes: "",

    // A evidência de importação pertence ao documento que a originou. Um clone
    // não foi importado de lugar nenhum, e dizer que foi falsificaria a origem.
    importEvidence: null,

    // O que se repete: a frota e os insumos, sem os números do dia.
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
  return `Traz ${partes.join(", ")}. Serviços, quantidades e clima ficam em branco.`;
}
