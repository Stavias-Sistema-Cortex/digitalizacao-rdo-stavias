/**
 * Geometria do esquemático linear da rodovia.
 *
 * Funções puras, sem DOM: recebem a projeção autoritativa do trecho e devolvem
 * posições percentuais prontas para desenhar. Nenhuma delas inventa quilômetro,
 * extensão ou faixa — segmento sem marcação quilométrica utilizável não é
 * posicionável e sai da régua em vez de ganhar um valor arbitrário.
 */

/**
 * De onde a régua tira cada bloco — sempre de um apontamento.
 *
 * <p>Houve uma quarta origem, `CADASTRO_MAPA`: o trecho desenhado no mapa
 * entrava aqui por conta própria, com a quilometragem guardada nas
 * propriedades da geometria. Eram duas declarações do mesmo trecho sem nada
 * que as reconciliasse — corrigir o RDO deixava o mapa mentindo, apagar o RDO
 * deixava o desenho de pé. Hoje o desenho escreve a linha de execução do RDO
 * do dia e chega aqui como `EXECUCAO_SERVICO`, igual a quem digitou.
 */
export type OrigemSegmento =
  | "PROGRAMACAO"
  | "RDO_CONTROLE"
  | "EXECUCAO_SERVICO";

/**
 * De onde o segmento veio.
 *
 * `SERVIDOR` é a projeção autoritativa. `DISPOSITIVO` é o que o apontador
 * lançou no RDO e ainda não subiu: aparece no trecho porque é o trabalho do
 * dia, mas nunca entra no consolidado enquanto o servidor não confirmar.
 */
export type ProcedenciaSegmento = "SERVIDOR" | "DISPOSITIVO";

export interface SegmentoTrecho {
  id: string;
  origem: OrigemSegmento;
  rdoId: string | null;
  numeroRdo: string | null;
  data: string | null;
  servicoNome: string | null;
  subtrecho: string | null;
  sentido: string | null;
  pista: string | null;
  faixa: string | null;
  kmInicial: number | null;
  kmFinal: number | null;
  estacaInicial: string | null;
  estacaFinal: string | null;
  extensaoM: number | null;
  larguraM: number | null;
  areaM2: number | null;
  massaTonelada: number | null;
  status: string | null;
  /** Situação do RDO de origem: RASCUNHO enquanto o apontador preenche. */
  rdoStatus: string | null;
  procedencia: ProcedenciaSegmento;
  /**
   * Verdadeira quando a pista não veio do próprio lançamento e foi herdada do
   * controle geométrico do mesmo RDO. O bloco é posicionado igual, mas o
   * detalhe conta que a pista é conclusão, não declaração do apontador.
   */
  pistaInferida: boolean;
}

export interface ResumoTrecho {
  totalSegmentos: number;
  totalRdos: number;
  /** Lançamentos ainda em rascunho, fora do consolidado. */
  totalRascunhos: number;
  /** Lançamentos que só existem neste aparelho, fora do consolidado. */
  totalPendentes: number;
  extensaoTotalM: number;
  areaTotalM2: number;
  massaTotalTonelada: number;
  primeiraExecucao: string | null;
  ultimaExecucao: string | null;
}

export interface Periodo {
  de: string | null;
  ate: string | null;
}

/** Um dia com produção lançada, agregado a partir dos RDOs daquela data. */
export interface DiaExecutado {
  data: string;
  totalSegmentos: number;
  totalRdos: number;
  extensaoM: number;
  massaTonelada: number;
  kmMin: number | null;
  kmMax: number | null;
}

export interface ProjecaoTrecho {
  obraId: string;
  obraNome: string;
  rodovia: string | null;
  operavel: boolean;
  sentidos: string[];
  faixas: string[];
  kmMin: number | null;
  kmMax: number | null;
  atualizadoEm: string | null;
  /** Extensão real do que a obra registrou, independente do filtro. */
  periodoDisponivel: Periodo;
  /** Período efetivamente aplicado nesta leitura. */
  periodoAplicado: Periodo;
  segmentos: SegmentoTrecho[];
  diasExecutados: DiaExecutado[];
  resumo: ResumoTrecho;
}

export interface EscalaKm {
  /** Menor quilômetro desenhado, já com a folga de contexto. */
  inicio: number;
  /** Maior quilômetro desenhado, já com a folga de contexto. */
  fim: number;
  /**
   * Verdadeiro quando o quilômetro diminui da esquerda para a direita, que é
   * como a rodovia é lida no sentido decrescente de estaqueamento.
   */
  decrescente: boolean;
}

export interface MarcoKm {
  km: number;
  /** Posição horizontal em porcentagem da largura da régua. */
  posicao: number;
  limite: "INICIO" | "FIM" | null;
}

export interface BlocoSegmento {
  segmento: SegmentoTrecho;
  /** Borda esquerda em porcentagem. */
  inicio: number;
  /** Largura em porcentagem. */
  largura: number;
}

export interface PistaEsquematica {
  /** Chave estável para renderização. */
  id: string;
  sentido: string;
  faixa: string;
  blocos: BlocoSegmento[];
}

/** Largura mínima de bloco, em porcentagem, para permanecer visível e clicável. */
const LARGURA_MINIMA_PERCENTUAL = 0.8;
/** Folga de contexto, em quilômetros, desenhada antes e depois da obra. */
const FOLGA_KM = 1;

function finito(valor: number | null | undefined): valor is number {
  return typeof valor === "number" && Number.isFinite(valor);
}

/**
 * Um segmento só é posicionável quando tem os dois extremos quilométricos.
 * Um extremo isolado descreveria um ponto, não um trecho.
 */
export function segmentoPosicionavel(segmento: SegmentoTrecho): boolean {
  return finito(segmento.kmInicial) && finito(segmento.kmFinal);
}

export function segmentosPosicionaveis(
  segmentos: readonly SegmentoTrecho[],
): SegmentoTrecho[] {
  return segmentos.filter(segmentoPosicionavel);
}

/**
 * Deriva a escala da régua a partir dos segmentos reais.
 *
 * O sentido de leitura vem do primeiro segmento executado que declara os dois
 * extremos: se o quilômetro final é menor que o inicial, a rodovia está sendo
 * percorrida no sentido decrescente e a régua acompanha essa leitura.
 */
export function calcularEscalaKm(
  segmentos: readonly SegmentoTrecho[],
): EscalaKm | null {
  const posicionaveis = segmentosPosicionaveis(segmentos);
  if (posicionaveis.length === 0) {
    return null;
  }

  const quilometros = posicionaveis.flatMap((segmento) => [
    segmento.kmInicial as number,
    segmento.kmFinal as number,
  ]);
  const menor = Math.min(...quilometros);
  const maior = Math.max(...quilometros);

  const referencia =
    posicionaveis.find((segmento) => segmento.origem !== "PROGRAMACAO") ??
    posicionaveis[0];
  const decrescente =
    (referencia.kmFinal as number) < (referencia.kmInicial as number);

  const inicio = Math.max(0, menor - FOLGA_KM);
  const fim = maior + FOLGA_KM;

  return {
    inicio,
    fim: fim > inicio ? fim : inicio + 1,
    decrescente,
  };
}

/** Converte um quilômetro em porcentagem horizontal dentro da escala. */
export function posicaoPercentual(km: number, escala: EscalaKm): number {
  const amplitude = escala.fim - escala.inicio;
  if (amplitude <= 0) {
    return 0;
  }
  const bruta = escala.decrescente
    ? (escala.fim - km) / amplitude
    : (km - escala.inicio) / amplitude;
  return Math.min(100, Math.max(0, bruta * 100));
}

/**
 * Extremos da obra na régua: onde a interdição começa e onde termina, no
 * sentido em que a rodovia é percorrida.
 */
export function limitesDaObra(
  segmentos: readonly SegmentoTrecho[],
  escala: EscalaKm,
): { inicio: number; fim: number } | null {
  const quilometros = segmentosPosicionaveis(segmentos).flatMap((segmento) => [
    segmento.kmInicial as number,
    segmento.kmFinal as number,
  ]);
  if (quilometros.length === 0) {
    return null;
  }
  const menor = Math.min(...quilometros);
  const maior = Math.max(...quilometros);
  return escala.decrescente
    ? { inicio: maior, fim: menor }
    : { inicio: menor, fim: maior };
}

/**
 * Quantos rótulos de quilômetro cabem na régua sem virar borrão.
 *
 * <p>Medido pela largura do painel: cada rótulo ocupa cerca de 46 px com o
 * "km" na frente, e menos que isso os números encostam. Vinte é o teto para
 * uma tela larga; o passo se ajusta para caber nele.
 */
const MAXIMO_DE_MARCOS = 20;

/** Passos que uma pessoa lê como quilometragem, e não como sobra de divisão. */
const PASSOS_LEGIVEIS = [1, 2, 5, 10, 20, 25, 50, 100, 200, 500, 1_000];

/**
 * O passo da régua para este vão.
 *
 * <p>A régua emitia um rótulo por quilômetro, sempre. Num trecho curto isso é
 * exatamente o certo; num filtro de "todo o período", que abre a escala de um
 * extremo da obra ao outro, viravam dezenas de números impressos uns sobre os
 * outros — um borrão que não diz nem onde começa nem onde termina. O vão é
 * que decide: quanto mais largo, mais espaçado o marco.
 */
export function passoDaRegua(amplitudeKm: number): number {
  if (!Number.isFinite(amplitudeKm) || amplitudeKm <= 0) {
    return 1;
  }
  return (
    PASSOS_LEGIVEIS.find(
      (passo) => amplitudeKm / passo <= MAXIMO_DE_MARCOS,
    ) ?? Math.ceil(amplitudeKm / MAXIMO_DE_MARCOS)
  );
}

/** Régua de quilômetros, sem rótulos de limite. */
export function marcosKm(escala: EscalaKm): MarcoKm[] {
  const primeiro = Math.ceil(escala.inicio);
  const ultimo = Math.floor(escala.fim);
  const passo = passoDaRegua(escala.fim - escala.inicio);
  const marcos: MarcoKm[] = [];
  // O primeiro marco cai num múltiplo do passo, para a régua ficar em números
  // redondos: uma escala que começa no km 101 com passo 5 marca 105, 110, 115
  // — e não 101, 106, 111, que ninguém lê como quilometragem.
  const inicioAlinhado = Math.ceil(primeiro / passo) * passo;
  for (let km = inicioAlinhado; km <= ultimo; km += passo) {
    marcos.push({
      km,
      posicao: posicaoPercentual(km, escala),
      limite: null,
    });
  }
  return escala.decrescente ? marcos.reverse() : marcos;
}

/**
 * Marcadores de início e fim da obra, posicionados no quilômetro exato.
 *
 * Ficam fora da régua inteira porque o limite quase nunca cai num quilômetro
 * redondo: um trecho que termina no km 170,5 precisa do marcador ali, e não
 * arredondado para o marco mais próximo.
 */
export function marcosDeLimite(
  escala: EscalaKm,
  segmentos: readonly SegmentoTrecho[],
): MarcoKm[] {
  const limites = limitesDaObra(segmentos, escala);
  if (!limites) {
    return [];
  }
  const marcadores: MarcoKm[] = [
    {
      km: limites.inicio,
      posicao: posicaoPercentual(limites.inicio, escala),
      limite: "INICIO",
    },
  ];
  if (limites.fim !== limites.inicio) {
    marcadores.push({
      km: limites.fim,
      posicao: posicaoPercentual(limites.fim, escala),
      limite: "FIM",
    });
  }
  return marcadores;
}

export function blocoDoSegmento(
  segmento: SegmentoTrecho,
  escala: EscalaKm,
): BlocoSegmento | null {
  if (!segmentoPosicionavel(segmento)) {
    return null;
  }
  const primeira = posicaoPercentual(segmento.kmInicial as number, escala);
  const segunda = posicaoPercentual(segmento.kmFinal as number, escala);
  const inicio = Math.min(primeira, segunda);
  const largura = Math.max(
    Math.abs(segunda - primeira),
    LARGURA_MINIMA_PERCENTUAL,
  );
  return {
    segmento,
    inicio,
    largura: Math.min(largura, 100 - inicio),
  };
}

const SENTIDO_NAO_DECLARADO = "Sentido não declarado";
const FAIXA_NAO_DECLARADA = "Faixa não declarada";

function rotuloSentido(segmento: SegmentoTrecho): string {
  return segmento.sentido ?? segmento.pista ?? SENTIDO_NAO_DECLARADO;
}

function rotuloFaixa(segmento: SegmentoTrecho): string {
  return segmento.faixa ?? FAIXA_NAO_DECLARADA;
}

/**
 * Agrupa os segmentos nas pistas do esquemático, uma por combinação de sentido
 * e faixa realmente presente nos dados. Combinações que a obra não usa não são
 * desenhadas: o esquemático mostra a rodovia que o RDO descreve, não um modelo
 * genérico de pista dupla.
 */
export function pistasDoTrecho(
  segmentos: readonly SegmentoTrecho[],
  escala: EscalaKm,
): PistaEsquematica[] {
  const pistas = new Map<string, PistaEsquematica>();

  for (const segmento of segmentosPosicionaveis(segmentos)) {
    const sentido = rotuloSentido(segmento);
    const faixa = rotuloFaixa(segmento);
    const id = `${sentido}::${faixa}`;
    const bloco = blocoDoSegmento(segmento, escala);
    if (!bloco) {
      continue;
    }
    const existente = pistas.get(id);
    if (existente) {
      existente.blocos.push(bloco);
    } else {
      pistas.set(id, { id, sentido, faixa, blocos: [bloco] });
    }
  }

  return [...pistas.values()].sort((a, b) =>
    a.sentido === b.sentido
      ? a.faixa.localeCompare(b.faixa, "pt-BR")
      : a.sentido.localeCompare(b.sentido, "pt-BR"),
  );
}

/**
 * Divide as pistas em dois lados do canteiro central quando a obra declara
 * exatamente dois sentidos. Com um sentido só, ou com mais de dois, não há
 * canteiro a representar e todas as pistas ficam de um lado.
 *
 * O que não declarou sentido sai num grupo próprio, `indefinidas`, em vez de
 * ser encostado num dos lados. Esse balde é ausência de dado: pôr essas pistas
 * junto de um sentido afirmaria de que lado da rodovia o trabalho aconteceu, e
 * mantê-las na conta dos sentidos apagava o canteiro de toda obra que tivesse
 * um único lançamento sem pista — que é o caso de todo serviço executado
 * anterior à captura de pista.
 */
export function ladosDoCanteiro(pistas: readonly PistaEsquematica[]): {
  superior: PistaEsquematica[];
  inferior: PistaEsquematica[];
  indefinidas: PistaEsquematica[];
  temCanteiro: boolean;
} {
  const declaradas = pistas.filter(
    (pista) => pista.sentido !== SENTIDO_NAO_DECLARADO,
  );
  const indefinidas = pistas.filter(
    (pista) => pista.sentido === SENTIDO_NAO_DECLARADO,
  );
  const sentidos = [...new Set(declaradas.map((pista) => pista.sentido))];

  // O canteiro afirma que a rodovia é de pista dupla, e isso só pode ser dito
  // quando existem exatamente dois sentidos realmente declarados.
  if (sentidos.length !== 2) {
    return {
      superior: [...declaradas],
      inferior: [],
      indefinidas,
      temCanteiro: false,
    };
  }
  return {
    superior: declaradas.filter((pista) => pista.sentido === sentidos[0]),
    inferior: declaradas.filter((pista) => pista.sentido === sentidos[1]),
    indefinidas,
    temCanteiro: true,
  };
}

export type EstadoSegmento =
  | "PROGRAMADO"
  | "PENDENTE"
  | "RASCUNHO"
  | "EXECUTADO"
  | "VALIDADO"
  | "REJEITADO";

/**
 * Estado real do segmento.
 *
 * Um lançamento que só existe neste aparelho é o mais recente de todos — é o
 * que acabou de ser apontado em campo — e por isso vem antes de qualquer outra
 * leitura: o servidor ainda não viu esse trabalho.
 *
 * Um lançamento cujo RDO ainda está em rascunho é o que a equipe está fazendo
 * agora — precisa aparecer, mas não pode ser lido como produção fechada. Só a
 * validação do serviço promove o bloco a validado; o simples envio do RDO
 * significa executado, não aprovado.
 */
export function estadoDoSegmento(segmento: SegmentoTrecho): EstadoSegmento {
  if (segmento.procedencia === "DISPOSITIVO") {
    return "PENDENTE";
  }
  if (segmento.origem === "PROGRAMACAO") {
    return "PROGRAMADO";
  }
  if (segmento.rdoStatus?.toUpperCase() === "RASCUNHO") {
    return "RASCUNHO";
  }
  const status = segmento.status?.toUpperCase() ?? "";
  if (status === "REJEITADA" || status === "CANCELADA") {
    return "REJEITADO";
  }
  if (status === "VALIDADA") {
    return "VALIDADO";
  }
  return "EXECUTADO";
}

/**
 * Rótulo do bloco. Usa exclusivamente valores presentes no segmento; a extensão
 * só entra quando foi medida.
 */
export function rotuloDoSegmento(segmento: SegmentoTrecho): string {
  const nome = segmento.servicoNome ?? segmento.subtrecho ?? "Serviço executado";
  if (!finito(segmento.extensaoM)) {
    return nome;
  }
  return `${nome} (${formatarMetros(segmento.extensaoM)})`;
}

export function formatarMetros(metros: number): string {
  return `${new Intl.NumberFormat("pt-BR", {
    maximumFractionDigits: metros >= 100 ? 0 : 2,
  }).format(metros)} m`;
}

/** Formata uma data ISO local (`AAAA-MM-DD`) sem deslocar por fuso. */
export function formatarData(data: string | null): string {
  if (!data) {
    return "—";
  }
  const partes = /^(\d{4})-(\d{2})-(\d{2})/.exec(data);
  if (!partes) {
    return data;
  }
  return `${partes[3]}/${partes[2]}/${partes[1]}`;
}

export function formatarPeriodo(periodo: Periodo): string {
  if (!periodo.de && !periodo.ate) {
    return "todo o período";
  }
  if (periodo.de && periodo.ate) {
    return periodo.de === periodo.ate
      ? formatarData(periodo.de)
      : `${formatarData(periodo.de)} a ${formatarData(periodo.ate)}`;
  }
  return periodo.de
    ? `a partir de ${formatarData(periodo.de)}`
    : `até ${formatarData(periodo.ate)}`;
}

export function formatarNumeroKm(km: number): string {
  return new Intl.NumberFormat("pt-BR", {
    maximumFractionDigits: 3,
  }).format(km);
}

export function formatarKm(km: number | null): string {
  return finito(km) ? `km ${formatarNumeroKm(km)}` : "—";
}

/**
 * Recorta uma projeção já carregada por período, sem ida à rede.
 *
 * É o que permite navegar pelos dias offline: o dispositivo tem a projeção
 * completa em cache e o filtro é aplicado sobre ela.
 */
export function recortarProjecao(
  projecao: ProjecaoTrecho,
  periodo: Periodo,
): ProjecaoTrecho {
  if (!periodo.de && !periodo.ate) {
    return projecao;
  }
  const dentro = (data: string | null): boolean =>
    Boolean(data) &&
    (!periodo.de || (data as string) >= periodo.de) &&
    (!periodo.ate || (data as string) <= periodo.ate);

  const segmentos = projecao.segmentos.filter((segmento) =>
    dentro(segmento.data),
  );
  const diasExecutados = projecao.diasExecutados.filter((dia) =>
    dentro(dia.data),
  );
  // Mesma regra do servidor: rascunho aparece, mas não entra no consolidado.
  // O que só existe neste aparelho fica de fora pelo mesmo motivo, com um
  // grau a mais de distância — nem o servidor recebeu ainda.
  const producao = segmentos.filter(
    (segmento) =>
      segmento.origem !== "PROGRAMACAO" &&
      segmento.procedencia !== "DISPOSITIVO",
  );
  const executados = producao.filter(
    (segmento) => segmento.rdoStatus?.toUpperCase() !== "RASCUNHO",
  );
  const rascunhos = producao.filter(
    (segmento) => segmento.rdoStatus?.toUpperCase() === "RASCUNHO",
  );
  const pendentes = segmentos.filter(
    (segmento) => segmento.procedencia === "DISPOSITIVO",
  );
  const somar = (valores: (number | null)[]): number =>
    valores.reduce<number>(
      (total, valor) => total + (typeof valor === "number" ? valor : 0),
      0,
    );
  const datas = executados
    .map((segmento) => segmento.data)
    .filter((data): data is string => Boolean(data))
    .sort();

  return {
    ...projecao,
    periodoAplicado: periodo,
    segmentos,
    diasExecutados,
    resumo: {
      totalSegmentos: segmentos.length,
      totalRascunhos: rascunhos.length,
      totalPendentes: pendentes.length,
      totalRdos: new Set(
        executados
          .map((segmento) => segmento.rdoId)
          .filter((id): id is string => Boolean(id)),
      ).size,
      extensaoTotalM: somar(executados.map((s) => s.extensaoM)),
      areaTotalM2: somar(executados.map((s) => s.areaM2)),
      massaTotalTonelada: somar(executados.map((s) => s.massaTonelada)),
      primeiraExecucao: datas.at(0) ?? null,
      ultimaExecucao: datas.at(-1) ?? null,
    },
  };
}

/**
 * Completa a pista do serviço executado com a do controle geométrico do mesmo
 * RDO — espelho exato da regra do servidor (`ObraTrechoService`), aplicado ao
 * que só existe neste aparelho e ainda não passou por lá.
 *
 * A conclusão é conservadora e sempre marcada em `pistaInferida`: vale o
 * controle cuja faixa de quilômetros encosta na do serviço e, sem interseção,
 * apenas o consenso de todos os controles daquele RDO. Divergência deixa o
 * segmento sem pista — palpite errado o poria na pista contrária.
 */
export function herdarPistaDoControle(
  segmentos: readonly SegmentoTrecho[],
): SegmentoTrecho[] {
  const controlesPorRdo = new Map<string, SegmentoTrecho[]>();
  for (const segmento of segmentos) {
    if (
      segmento.origem === "RDO_CONTROLE" &&
      segmento.rdoId &&
      segmento.pista
    ) {
      const atual = controlesPorRdo.get(segmento.rdoId) ?? [];
      atual.push(segmento);
      controlesPorRdo.set(segmento.rdoId, atual);
    }
  }
  if (controlesPorRdo.size === 0) {
    return [...segmentos];
  }

  return segmentos.map((segmento) => {
    if (
      segmento.origem !== "EXECUCAO_SERVICO" ||
      segmento.pista !== null ||
      !segmento.rdoId
    ) {
      return segmento;
    }
    const candidatos = controlesPorRdo.get(segmento.rdoId);
    if (!candidatos) {
      return segmento;
    }
    const sobrepostos = candidatos.filter((controle) =>
      sobrepoeEmKm(controle, segmento),
    );
    const base = sobrepostos.length > 0 ? sobrepostos : candidatos;
    const pista = consenso(base, (controle) => controle.pista);
    if (!pista) {
      return segmento;
    }
    return {
      ...segmento,
      pista,
      faixa: segmento.faixa ?? consenso(base, (controle) => controle.faixa),
      pistaInferida: true,
    };
  });
}

/** Serviço sem os dois quilômetros não encosta em controle nenhum. */
function sobrepoeEmKm(a: SegmentoTrecho, b: SegmentoTrecho): boolean {
  if (!segmentoPosicionavel(a) || !segmentoPosicionavel(b)) {
    return false;
  }
  const aMin = Math.min(a.kmInicial as number, a.kmFinal as number);
  const aMax = Math.max(a.kmInicial as number, a.kmFinal as number);
  const bMin = Math.min(b.kmInicial as number, b.kmFinal as number);
  const bMax = Math.max(b.kmInicial as number, b.kmFinal as number);
  return aMin <= bMax && bMin <= aMax;
}

/** O valor único declarado pelo grupo, ou nulo quando eles discordam. */
function consenso(
  segmentos: readonly SegmentoTrecho[],
  campo: (segmento: SegmentoTrecho) => string | null,
): string | null {
  const valores = new Set(
    segmentos.map(campo).filter((valor): valor is string => Boolean(valor)),
  );
  return valores.size === 1 ? [...valores][0] : null;
}

/** Chave do dia agregado, a partir da data do lançamento. */
function diaDoSegmento(segmento: SegmentoTrecho): string | null {
  return segmento.data;
}

function agregarDias(
  segmentos: readonly SegmentoTrecho[],
): Map<string, DiaExecutado> {
  const porDia = new Map<string, DiaExecutado>();
  for (const segmento of segmentos) {
    const data = diaDoSegmento(segmento);
    if (segmento.origem === "PROGRAMACAO" || !data) {
      continue;
    }
    const atual = porDia.get(data);
    const kms = [segmento.kmInicial, segmento.kmFinal].filter(finito);
    porDia.set(data, {
      data,
      totalSegmentos: (atual?.totalSegmentos ?? 0) + 1,
      totalRdos: atual?.totalRdos ?? 0,
      extensaoM: (atual?.extensaoM ?? 0) + (finito(segmento.extensaoM) ? segmento.extensaoM : 0),
      massaTonelada:
        (atual?.massaTonelada ?? 0) +
        (finito(segmento.massaTonelada) ? segmento.massaTonelada : 0),
      kmMin: menorDefinido(atual?.kmMin ?? null, kms),
      kmMax: maiorDefinido(atual?.kmMax ?? null, kms),
    });
  }
  // O total de RDOs do dia é a contagem de RDOs distintos, não de segmentos.
  for (const [data, dia] of porDia) {
    const rdos = new Set(
      segmentos
        .filter(
          (segmento) =>
            segmento.origem !== "PROGRAMACAO" &&
            segmento.data === data &&
            segmento.rdoId,
        )
        .map((segmento) => segmento.rdoId as string),
    );
    porDia.set(data, { ...dia, totalRdos: rdos.size });
  }
  return porDia;
}

function menorDefinido(atual: number | null, valores: number[]): number | null {
  const candidatos = atual === null ? valores : [atual, ...valores];
  return candidatos.length > 0 ? Math.min(...candidatos) : null;
}

function maiorDefinido(atual: number | null, valores: number[]): number | null {
  const candidatos = atual === null ? valores : [atual, ...valores];
  return candidatos.length > 0 ? Math.max(...candidatos) : null;
}

/**
 * Junta à projeção do servidor o que o apontador lançou e ainda não subiu.
 *
 * O RDO preenchido em campo é o trabalho do dia: ele precisa desenhar no
 * trecho antes de sincronizar, ou o acompanhamento só serviria para quem está
 * online. O que entra fica marcado como `DISPOSITIVO` e é somado ao dia
 * correspondente — para o calendário poder selecioná-lo —, mas nunca ao
 * consolidado, que continua sendo só o que o servidor confirmou.
 *
 * Um RDO que já subiu volta pela projeção autoritativa; por isso o lançamento
 * local do mesmo RDO é descartado aqui, em vez de aparecer duas vezes.
 */
export function mesclarLancamentosLocais(
  projecao: ProjecaoTrecho,
  locais: readonly SegmentoTrecho[],
): ProjecaoTrecho {
  const conhecidos = new Set(
    projecao.segmentos
      .map((segmento) => segmento.rdoId)
      .filter((id): id is string => Boolean(id)),
  );
  const novos = locais.filter(
    (segmento) => !segmento.rdoId || !conhecidos.has(segmento.rdoId),
  );
  if (novos.length === 0) {
    return projecao;
  }

  const segmentos = [...projecao.segmentos, ...novos];
  const quilometros = segmentosPosicionaveis(novos).flatMap((segmento) => [
    segmento.kmInicial as number,
    segmento.kmFinal as number,
  ]);
  const dias = agregarDias(segmentos);

  return {
    ...projecao,
    segmentos,
    kmMin: menorDefinido(projecao.kmMin, quilometros),
    kmMax: maiorDefinido(projecao.kmMax, quilometros),
    diasExecutados: [...dias.values()].sort((a, b) =>
      a.data.localeCompare(b.data),
    ),
    periodoDisponivel: ampliarPeriodo(projecao.periodoDisponivel, novos),
    resumo: {
      ...projecao.resumo,
      totalSegmentos: segmentos.length,
      totalPendentes: projecao.resumo.totalPendentes + novos.length,
    },
  };
}

/** O calendário precisa alcançar o dia lançado agora, ainda não sincronizado. */
function ampliarPeriodo(
  periodo: Periodo,
  segmentos: readonly SegmentoTrecho[],
): Periodo {
  const datas = segmentos
    .map((segmento) => segmento.data)
    .filter((data): data is string => Boolean(data))
    .sort();
  if (datas.length === 0) {
    return periodo;
  }
  const primeira = datas[0];
  const ultima = datas[datas.length - 1];
  return {
    de: periodo.de && periodo.de < primeira ? periodo.de : primeira,
    ate: periodo.ate && periodo.ate > ultima ? periodo.ate : ultima,
  };
}

/**
 * Projeção montada só com o que existe no aparelho.
 *
 * Usada quando nem a rede nem o cache respondem — aparelho novo cujo primeiro
 * RDO foi preenchido em campo, sem nunca ter aberto a obra online. Sem isto o
 * apontador veria um erro no lugar do próprio trabalho.
 */
export function projecaoDoDispositivo(
  obra: { id: string; nome: string; operavel: boolean },
  segmentos: readonly SegmentoTrecho[],
  rodovia: string | null,
): ProjecaoTrecho {
  const posicionaveis = segmentosPosicionaveis(segmentos);
  const quilometros = posicionaveis.flatMap((segmento) => [
    segmento.kmInicial as number,
    segmento.kmFinal as number,
  ]);
  const dias = agregarDias(segmentos);
  const periodo = ampliarPeriodo({ de: null, ate: null }, segmentos);

  return {
    obraId: obra.id,
    obraNome: obra.nome,
    rodovia,
    operavel: obra.operavel,
    sentidos: [
      ...new Set(
        segmentos
          .map((segmento) => segmento.sentido)
          .filter((valor): valor is string => Boolean(valor)),
      ),
    ],
    faixas: [
      ...new Set(
        segmentos
          .map((segmento) => segmento.faixa)
          .filter((valor): valor is string => Boolean(valor)),
      ),
    ],
    kmMin: quilometros.length > 0 ? Math.min(...quilometros) : null,
    kmMax: quilometros.length > 0 ? Math.max(...quilometros) : null,
    atualizadoEm: null,
    periodoDisponivel: periodo,
    periodoAplicado: { de: null, ate: null },
    segmentos: [...segmentos],
    diasExecutados: [...dias.values()].sort((a, b) =>
      a.data.localeCompare(b.data),
    ),
    resumo: {
      totalSegmentos: segmentos.length,
      totalRdos: 0,
      totalRascunhos: 0,
      totalPendentes: segmentos.length,
      extensaoTotalM: 0,
      areaTotalM2: 0,
      massaTotalTonelada: 0,
      primeiraExecucao: null,
      ultimaExecucao: null,
    },
  };
}
