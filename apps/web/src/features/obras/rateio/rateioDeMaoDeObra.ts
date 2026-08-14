/**
 * O rateio de mão de obra entre obras, apurado a partir do que os RDOs dizem.
 *
 * <p>A pergunta que este módulo responde é a que antes se fazia numa planilha
 * mantida à mão todo mês: em que obra cada pessoa esteve, dia a dia, e que
 * fatia do período coube a cada obra. A resposta não é declarada por ninguém —
 * ela já está escrita nos RDOs, um por obra e por dia, com a mão de obra
 * apontada em campo. O que faltava era ler.
 *
 * <p>O módulo é puro de propósito: entra uma lista de apontamentos, sai a
 * matriz. Quem busca os apontamentos — o aparelho, lendo os RDOs que já
 * sincronizou, ou o servidor, lendo o banco inteiro — é problema de outro
 * arquivo. Isso mantém uma única regra de rateio no sistema, em vez de uma no
 * cliente e outra no servidor divergindo em silêncio.
 *
 * <p>Duas decisões merecem ser ditas em voz alta, porque a planilha antiga não
 * as tomava:
 *
 * <ul>
 *   <li><b>O dia vale um, sempre.</b> Quem foi apontado em duas obras no mesmo
 *       dia entra com meio dia em cada uma. A planilha escrevia uma obra só por
 *       célula e o dia dividido se perdia; aqui ele é dividido em partes iguais,
 *       porque o RDO não declara quantas horas couberam a cada frente e inventar
 *       essa proporção seria pior do que reparti-la.</li>
 *   <li><b>Só conta dia apontado.</b> O denominador é o número de dias em que a
 *       pessoa aparece em algum RDO, não o número de dias do mês. Férias,
 *       afastamento e chuva não viram fatia de obra nenhuma — e o percentual
 *       continua somando 100% do tempo efetivamente trabalhado.</li>
 * </ul>
 */

/** Uma pessoa apontada num RDO: a menor unidade de verdade deste cálculo. */
export interface ApontamentoDeMaoDeObra {
  /** Nulo quando a pessoa foi somada à mão em campo, sem cadastro. */
  colaboradorId: string | null;
  nome: string;
  /** O cargo do dia, como o RDO registrou. */
  funcao: string;
  obraId: string;
  /**
   * O nome da obra, quando quem entregou o apontamento o conhece.
   *
   * <p>Vazio na leitura local, onde o nome vem da lista de obras do aparelho.
   * O servidor manda preenchido para que uma obra recém-criada não apareça
   * como identificador cru em quem ainda não baixou a lista.
   */
  obraNome: string;
  /** `YYYY-MM-DD`. */
  data: string;
  /** A frente: quem responde pelo RDO em que a pessoa foi apontada. */
  encarregado: string;
  rdoId: string;
  /**
   * O número do RDO, quando quem entregou o apontamento o conhece.
   *
   * <p>É o que dá à célula do dia o rastro de volta ao documento: a matriz
   * responde "quem, onde, quando", e o número responde "segundo qual RDO".
   */
  numeroRdo?: string;
}

/** O que uma pessoa fez num dia, já com o dia repartido entre as obras. */
export interface DiaDoColaborador {
  data: string;
  /** Obras do dia, em ordem estável; mais de uma significa dia dividido. */
  obraIds: readonly string[];
  /** A fatia que coube a cada obra naquele dia: 1 dividido pelas obras. */
  fracaoPorObra: number;
  /**
   * Números dos RDOs que apontaram a pessoa neste dia, em ordem estável.
   *
   * <p>Vazio quando nenhum apontamento trouxe o número — a célula continua
   * de pé, só sem o rastro.
   */
  rdos: readonly string[];
}

/** A linha de uma pessoa na matriz. */
export interface RateioDeColaborador {
  /** Identidade estável: o cadastro quando existe, o nome quando não. */
  chave: string;
  colaboradorId: string | null;
  nome: string;
  funcao: string;
  encarregado: string;
  /** Dias em que a pessoa aparece em algum RDO do período. */
  diasApontados: number;
  /** Por data (`YYYY-MM-DD`), o que a pessoa fez naquele dia. */
  dias: ReadonlyMap<string, DiaDoColaborador>;
  /** Por obra, o número (fracionário) de dias que couberam a ela. */
  diasPorObra: ReadonlyMap<string, number>;
  /** Por obra, a fatia do período: soma 1 quando há algum dia apontado. */
  fracaoPorObra: ReadonlyMap<string, number>;
}

/** O total de uma obra no período. */
export interface TotalDaObra {
  obraId: string;
  /** Soma dos dias fracionários de todas as pessoas. */
  diasApontados: number;
  /** Quantas pessoas distintas passaram pela obra. */
  pessoas: number;
}

/** O total de uma frente no período. */
export interface TotalDoEncarregado {
  encarregado: string;
  pessoas: number;
  diasApontados: number;
}

export interface RateioDoPeriodo {
  colaboradores: readonly RateioDeColaborador[];
  /** Obras que aparecem em algum apontamento, em ordem de volume. */
  obraIds: readonly string[];
  totaisPorObra: ReadonlyMap<string, TotalDaObra>;
  totaisPorEncarregado: readonly TotalDoEncarregado[];
  /** Dias distintos com apontamento, em ordem crescente. */
  diasComApontamento: readonly string[];
  /** Soma dos dias fracionários de todo mundo: o tamanho do período apurado. */
  diasApontadosNoTotal: number;
}

/** Sem cadastro, o nome é a identidade — então precisa ser comparável. */
export function normalizarNome(nome: string): string {
  return nome
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .toUpperCase();
}

/**
 * O cadastro de cada nome, quando o nome aponta para uma pessoa só.
 *
 * <p>Existe porque a mesma pessoa chega ao rateio por duas portas de identidade
 * diferentes. Quem é apontado na equipe costuma vir com cadastro; quem assina o
 * documento — o que preencheu, o que apontou — vem só com o nome digitado, e
 * <b>quem preencheu nunca traz cadastro</b>: não há campo para ele no RDO. Um
 * mesmo encarregado que preencheu o RDO da segunda e foi apontado na equipe na
 * terça virava duas linhas na matriz, cada uma com o mês inteiro para si.
 *
 * <p>Dentro de um RDO só isso já era tratado — quem assina não entra se a
 * equipe já o trouxe. O que faltava era atravessar RDOs, e só quem vê o período
 * inteiro consegue: é aqui.
 *
 * <p>Nome repetido em dois cadastros não resolve nada e fica de fora. Escolher
 * um dos dois lançaria os dias de um homônimo na conta do outro, o que é pior
 * do que a linha a mais que já existe hoje — e a linha a mais é o que ele
 * continua tendo.
 */
function cadastrosPorNome(
  apontamentos: readonly ApontamentoDeMaoDeObra[],
): Map<string, string> {
  const cadastros = new Map<string, string>();
  const homonimos = new Set<string>();

  for (const apontamento of apontamentos) {
    const cadastro = apontamento.colaboradorId?.trim();
    if (!cadastro) continue;
    const nome = normalizarNome(texto(apontamento.nome));
    if (!nome) continue;
    const jaVisto = cadastros.get(nome);
    if (jaVisto === undefined) {
      cadastros.set(nome, cadastro);
    } else if (jaVisto !== cadastro) {
      homonimos.add(nome);
    }
  }

  for (const nome of homonimos) {
    cadastros.delete(nome);
  }
  return cadastros;
}

/** Identidade estável: o cadastro quando existe ou é alcançável, o nome quando não. */
function identidadeDaPessoa(
  apontamento: ApontamentoDeMaoDeObra,
  cadastros: ReadonlyMap<string, string>,
): { chave: string; colaboradorId: string | null } {
  const nome = normalizarNome(texto(apontamento.nome));
  const cadastro = apontamento.colaboradorId?.trim() || cadastros.get(nome);
  return cadastro
    ? { chave: `id:${cadastro}`, colaboradorId: cadastro }
    : { chave: `nome:${nome}`, colaboradorId: null };
}

/**
 * O texto que o RDO trouxe, ou vazio.
 *
 * <p>Espaço em branco e nulo viram a mesma coisa para que a tela não precise
 * decidir entre `"  "` e ausência — ela só pergunta se está vazio.
 */
function texto(valor: string | null | undefined): string {
  return typeof valor === "string" ? valor.trim() : "";
}

/** Conta ocorrências para depois escolher a mais frequente. */
class Votacao {
  private readonly votos = new Map<string, number>();
  private ultimo = "";
  private dataDoUltimo = "";

  registrar(valor: string, data: string): void {
    if (!valor) return;
    this.votos.set(valor, (this.votos.get(valor) ?? 0) + 1);
    if (data >= this.dataDoUltimo) {
      this.dataDoUltimo = data;
      this.ultimo = valor;
    }
  }

  /**
   * O mais frequente; empate decidido pelo mais recente.
   *
   * <p>Frequência antes de recência porque a função e a frente de uma pessoa
   * são estáveis no mês, e um único RDO em que alguém cobriu outra equipe não
   * deve reescrever a linha inteira. O empate cai para o mais recente porque
   * quem mudou de frente na metade do mês está, hoje, na frente nova.
   */
  vencedor(): string {
    let escolhido = "";
    let melhor = 0;
    for (const [valor, quantidade] of this.votos) {
      if (quantidade > melhor) {
        melhor = quantidade;
        escolhido = valor;
      }
    }
    if (!escolhido) return this.ultimo;
    const empatados = [...this.votos.entries()].filter(
      ([, quantidade]) => quantidade === melhor,
    );
    if (empatados.length > 1 && this.votos.get(this.ultimo) === melhor) {
      return this.ultimo;
    }
    return escolhido;
  }
}

interface AcumuladorDePessoa {
  chave: string;
  colaboradorId: string | null;
  nome: Votacao;
  funcao: Votacao;
  encarregado: Votacao;
  /** data -> obras daquele dia (conjunto: o mesmo par não conta duas vezes). */
  obrasPorDia: Map<string, Set<string>>;
  /** data -> números dos RDOs do dia, para a célula apontar o documento. */
  rdosPorDia: Map<string, Set<string>>;
  primeiroNome: string;
}

/**
 * Apura o rateio de um conjunto de apontamentos.
 *
 * <p>Não filtra período nem obra: quem chama já entrega o recorte que quer ver.
 * Assim a mesma função serve ao mês fechado, à semana e ao acumulado da obra.
 */
export function apurarRateio(
  apontamentos: readonly ApontamentoDeMaoDeObra[],
): RateioDoPeriodo {
  const pessoas = new Map<string, AcumuladorDePessoa>();
  const cadastros = cadastrosPorNome(apontamentos);

  for (const apontamento of apontamentos) {
    const obraId = texto(apontamento.obraId);
    const data = texto(apontamento.data);
    const nome = texto(apontamento.nome);
    if (!obraId || !data) continue;
    // Sem cadastro e sem nome não há a quem atribuir o dia — e inventar uma
    // linha "sem nome" só encheria a tela de gente que não existe.
    if (!apontamento.colaboradorId?.trim() && !nome) continue;

    const { chave, colaboradorId } = identidadeDaPessoa(apontamento, cadastros);
    let pessoa = pessoas.get(chave);
    if (!pessoa) {
      pessoa = {
        chave,
        colaboradorId,
        nome: new Votacao(),
        funcao: new Votacao(),
        encarregado: new Votacao(),
        obrasPorDia: new Map(),
        rdosPorDia: new Map(),
        primeiroNome: nome,
      };
      pessoas.set(chave, pessoa);
    }
    pessoa.nome.registrar(nome, data);
    pessoa.funcao.registrar(texto(apontamento.funcao), data);
    pessoa.encarregado.registrar(texto(apontamento.encarregado), data);

    const obrasDoDia = pessoa.obrasPorDia.get(data) ?? new Set<string>();
    obrasDoDia.add(obraId);
    pessoa.obrasPorDia.set(data, obrasDoDia);

    const numeroRdo = texto(apontamento.numeroRdo);
    if (numeroRdo) {
      const rdosDoDia = pessoa.rdosPorDia.get(data) ?? new Set<string>();
      rdosDoDia.add(numeroRdo);
      pessoa.rdosPorDia.set(data, rdosDoDia);
    }
  }

  const totaisPorObra = new Map<string, TotalDaObra>();
  const pessoasPorObra = new Map<string, Set<string>>();
  const diasComApontamento = new Set<string>();
  const colaboradores: RateioDeColaborador[] = [];
  let diasApontadosNoTotal = 0;

  for (const pessoa of pessoas.values()) {
    const dias = new Map<string, DiaDoColaborador>();
    const diasPorObra = new Map<string, number>();

    for (const [data, obrasDoDia] of pessoa.obrasPorDia) {
      const obraIds = [...obrasDoDia].sort();
      const fracaoPorObra = 1 / obraIds.length;
      const rdos = [...(pessoa.rdosPorDia.get(data) ?? [])].sort();
      dias.set(data, { data, obraIds, fracaoPorObra, rdos });
      diasComApontamento.add(data);

      for (const obraId of obraIds) {
        diasPorObra.set(obraId, (diasPorObra.get(obraId) ?? 0) + fracaoPorObra);

        const total = totaisPorObra.get(obraId) ?? {
          obraId,
          diasApontados: 0,
          pessoas: 0,
        };
        total.diasApontados += fracaoPorObra;
        totaisPorObra.set(obraId, total);

        const quadro = pessoasPorObra.get(obraId) ?? new Set<string>();
        quadro.add(pessoa.chave);
        pessoasPorObra.set(obraId, quadro);
      }
    }

    const diasApontados = pessoa.obrasPorDia.size;
    diasApontadosNoTotal += diasApontados;
    const fracaoPorObra = new Map<string, number>();
    for (const [obraId, quantidade] of diasPorObra) {
      fracaoPorObra.set(
        obraId,
        diasApontados > 0 ? quantidade / diasApontados : 0,
      );
    }

    colaboradores.push({
      chave: pessoa.chave,
      colaboradorId: pessoa.colaboradorId,
      nome: pessoa.nome.vencedor() || pessoa.primeiroNome,
      funcao: pessoa.funcao.vencedor(),
      encarregado: pessoa.encarregado.vencedor(),
      diasApontados,
      dias,
      diasPorObra,
      fracaoPorObra,
    });
  }

  for (const [obraId, quadro] of pessoasPorObra) {
    const total = totaisPorObra.get(obraId);
    if (total) total.pessoas = quadro.size;
  }

  const totaisPorEncarregado = apurarEncarregados(colaboradores);

  colaboradores.sort(compararLinhas);

  const obraIds = [...totaisPorObra.values()]
    .sort((a, b) =>
      b.diasApontados - a.diasApontados || a.obraId.localeCompare(b.obraId),
    )
    .map((total) => total.obraId);

  return {
    colaboradores,
    obraIds,
    totaisPorObra,
    totaisPorEncarregado,
    diasComApontamento: [...diasComApontamento].sort(),
    diasApontadosNoTotal,
  };
}

/**
 * A ordem da tela: frente primeiro, nome depois.
 *
 * <p>É como a planilha era lida — cada encarregado conferindo o próprio bloco.
 * Quem não tem frente declarada vai para o fim, junto, em vez de se espalhar
 * entre os blocos de quem tem.
 */
function compararLinhas(
  a: RateioDeColaborador,
  b: RateioDeColaborador,
): number {
  if (a.encarregado !== b.encarregado) {
    if (!a.encarregado) return 1;
    if (!b.encarregado) return -1;
    return a.encarregado.localeCompare(b.encarregado, "pt-BR");
  }
  return a.nome.localeCompare(b.nome, "pt-BR");
}

function apurarEncarregados(
  colaboradores: readonly RateioDeColaborador[],
): TotalDoEncarregado[] {
  const totais = new Map<string, TotalDoEncarregado>();
  for (const colaborador of colaboradores) {
    const encarregado = colaborador.encarregado;
    const total = totais.get(encarregado) ?? {
      encarregado,
      pessoas: 0,
      diasApontados: 0,
    };
    total.pessoas += 1;
    total.diasApontados += colaborador.diasApontados;
    totais.set(encarregado, total);
  }
  return [...totais.values()].sort(
    (a, b) =>
      b.pessoas - a.pessoas ||
      a.encarregado.localeCompare(b.encarregado, "pt-BR"),
  );
}

/**
 * Os dias do período, inclusive os sem apontamento.
 *
 * <p>A matriz mostra o calendário inteiro, não só os dias em que houve serviço:
 * o buraco de uma semana parada é justamente o que o gestor procura, e uma
 * tabela que pula os dias vazios esconde exatamente isso.
 */
export function diasDoPeriodo(inicio: string, fim: string): string[] {
  const dias: string[] = [];
  if (!inicio || !fim || fim < inicio) return dias;
  const limite = new Date(`${fim}T12:00:00Z`);
  const cursor = new Date(`${inicio}T12:00:00Z`);
  if (Number.isNaN(cursor.getTime()) || Number.isNaN(limite.getTime())) {
    return dias;
  }
  // Teto defensivo: um período absurdo (data digitada errada, ano trocado) não
  // pode virar um laço que trava a aba antes de alguém perceber o engano.
  const MAXIMO_DE_DIAS = 400;
  while (cursor <= limite && dias.length < MAXIMO_DE_DIAS) {
    dias.push(cursor.toISOString().slice(0, 10));
    cursor.setUTCDate(cursor.getUTCDate() + 1);
  }
  return dias;
}

/** O primeiro e o último dia do mês de uma data `YYYY-MM-DD` ou `YYYY-MM`. */
export function mesDe(data: string): { inicio: string; fim: string } {
  const mes = data.slice(0, 7);
  const [ano, numeroDoMes] = mes.split("-").map((parte) => Number(parte));
  if (!Number.isFinite(ano) || !Number.isFinite(numeroDoMes)) {
    return { inicio: "", fim: "" };
  }
  const ultimoDia = new Date(Date.UTC(ano, numeroDoMes, 0)).getUTCDate();
  return {
    inicio: `${mes}-01`,
    fim: `${mes}-${String(ultimoDia).padStart(2, "0")}`,
  };
}
