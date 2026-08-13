import type { SegmentoTrecho } from "../trecho/trechoGeometry";
import { estadoDoSegmento } from "../trecho/trechoGeometry";
import type {
  OperationalFeature,
  OperationalFeatureCollection,
} from "./mapGeometry";

/**
 * O eixo da obra: a rodovia desenhada uma vez, com quilômetro nas pontas.
 *
 * Até aqui, um trecho só aparecia no mapa se alguém o tivesse desenhado à mão
 * ali. Quem apontou o RDO pelo quilômetro — que é como a obra fala — não via
 * nada: o dado existia, com km inicial e final declarados, e a única tela que
 * mostra onde o trabalho aconteceu ficava vazia.
 *
 * O eixo resolve isso invertendo o gesto. Desenha-se a rodovia uma vez, diz-se
 * qual quilômetro está em cada extremidade, e a partir daí todo apontamento com
 * quilômetro se apoia sozinho sobre ela. O desenho deixa de ser uma tarefa por
 * RDO e vira cadastro da obra.
 *
 * Nada aqui inventa posição. O quilômetro continua morando no apontamento, e o
 * eixo só oferece a régua: um trecho sem os dois extremos quilométricos não é
 * posicionável e não é desenhado, em vez de ganhar um lugar arbitrário.
 */

/** Categoria persistida em `obra_geometria` para o eixo. */
export const CATEGORIA_EIXO = "EIXO_OBRA";

/** Marca as feições que o eixo derivou, para o mapa saber que não são desenho. */
export const PROPRIEDADE_DERIVADA = "derivadoDoEixo";

export type Coordenada = readonly [number, number];

export interface EixoDaObra {
  /** Identidade da geometria de origem, para o mapa poder falar dela. */
  id: string;
  /** Vértices em [lng, lat], na ordem em que a linha foi traçada. */
  coordenadas: readonly Coordenada[];
  /** Quilômetro no primeiro vértice. */
  kmInicial: number;
  /** Quilômetro no último vértice. */
  kmFinal: number;
}

function numeroFinito(valor: unknown): number | null {
  return typeof valor === "number" && Number.isFinite(valor) ? valor : null;
}

/**
 * Lê o quilômetro de uma propriedade que pode ter vindo como texto.
 *
 * A geometria trafega por JSON e volta do servidor com o que foi gravado; um
 * número que virou string continua sendo o quilômetro que a pessoa digitou, e
 * descartá-lo por causa do tipo apagaria o eixo inteiro.
 */
function kmDaPropriedade(valor: unknown): number | null {
  const direto = numeroFinito(valor);
  if (direto !== null) {
    return direto;
  }
  if (typeof valor !== "string" || !valor.trim()) {
    return null;
  }
  return numeroFinito(Number(valor.replace(",", ".")));
}

function coordenadasDaLinha(geometry: unknown): Coordenada[] | null {
  if (
    typeof geometry !== "object" ||
    geometry === null ||
    (geometry as { type?: unknown }).type !== "LineString"
  ) {
    return null;
  }
  const bruto = (geometry as { coordinates?: unknown }).coordinates;
  if (!Array.isArray(bruto) || bruto.length < 2) {
    return null;
  }
  const pontos: Coordenada[] = [];
  for (const par of bruto) {
    if (!Array.isArray(par) || par.length < 2) {
      return null;
    }
    const lng = numeroFinito(par[0]);
    const lat = numeroFinito(par[1]);
    if (lng === null || lat === null) {
      return null;
    }
    pontos.push([lng, lat]);
  }
  return pontos;
}

/**
 * Encontra o eixo dentro da coleção que o mapa já carregou.
 *
 * Uma obra tem um eixo; se houver mais de um vigente — correção que subiu duas
 * vezes, por exemplo —, vale o último da coleção, que é o mais recente na
 * ordenação da leitura. Escolher em silêncio é melhor do que não desenhar nada,
 * e o desenho continua visível para quem quiser encerrar o antigo.
 */
export function lerEixoDaColecao(
  collection: OperationalFeatureCollection,
): EixoDaObra | null {
  let encontrado: EixoDaObra | null = null;
  for (const feature of collection.features) {
    if (feature.properties.categoria !== CATEGORIA_EIXO) {
      continue;
    }
    const coordenadas = coordenadasDaLinha(feature.geometry);
    const kmInicial = kmDaPropriedade(feature.properties.kmInicial);
    const kmFinal = kmDaPropriedade(feature.properties.kmFinal);
    if (!coordenadas || kmInicial === null || kmFinal === null) {
      continue;
    }
    // Sem amplitude não há régua: os dois extremos no mesmo quilômetro não
    // dizem onde cai nenhum ponto entre eles.
    if (kmInicial === kmFinal) {
      continue;
    }
    encontrado = {
      id: feature.id,
      coordenadas,
      kmInicial,
      kmFinal,
    };
  }
  return encontrado;
}

const RAIO_DA_TERRA_M = 6_371_008.8;

function radianos(graus: number): number {
  return (graus * Math.PI) / 180;
}

/**
 * Distância entre dois pontos sobre a esfera, em metros.
 *
 * Haversine e não Euclides: um eixo de rodovia tem dezenas de quilômetros, e
 * medir em graus faria o quilômetro valer coisas diferentes conforme a latitude
 * — o trecho sairia deslocado justamente nas obras mais longas.
 */
function distanciaM(a: Coordenada, b: Coordenada): number {
  const deltaLat = radianos(b[1] - a[1]);
  const deltaLng = radianos(b[0] - a[0]);
  const seno =
    Math.sin(deltaLat / 2) ** 2 +
    Math.cos(radianos(a[1])) *
      Math.cos(radianos(b[1])) *
      Math.sin(deltaLng / 2) ** 2;
  return 2 * RAIO_DA_TERRA_M * Math.asin(Math.min(1, Math.sqrt(seno)));
}

interface Percurso {
  /** Distância acumulada até cada vértice, do primeiro ao último. */
  acumulado: number[];
  comprimento: number;
}

function percorrer(coordenadas: readonly Coordenada[]): Percurso {
  const acumulado = [0];
  for (let i = 1; i < coordenadas.length; i += 1) {
    acumulado.push(
      acumulado[i - 1] + distanciaM(coordenadas[i - 1], coordenadas[i]),
    );
  }
  return { acumulado, comprimento: acumulado[acumulado.length - 1] };
}

function interpolar(a: Coordenada, b: Coordenada, fracao: number): Coordenada {
  return [a[0] + (b[0] - a[0]) * fracao, a[1] + (b[1] - a[1]) * fracao];
}

/** Onde, ao longo da linha, cai uma distância medida do primeiro vértice. */
function pontoNaDistancia(
  coordenadas: readonly Coordenada[],
  percurso: Percurso,
  distancia: number,
): Coordenada {
  if (distancia <= 0) {
    return coordenadas[0];
  }
  if (distancia >= percurso.comprimento) {
    return coordenadas[coordenadas.length - 1];
  }
  for (let i = 1; i < coordenadas.length; i += 1) {
    if (percurso.acumulado[i] < distancia) {
      continue;
    }
    const trechoInicio = percurso.acumulado[i - 1];
    const vao = percurso.acumulado[i] - trechoInicio;
    const fracao = vao <= 0 ? 0 : (distancia - trechoInicio) / vao;
    return interpolar(coordenadas[i - 1], coordenadas[i], fracao);
  }
  return coordenadas[coordenadas.length - 1];
}

/**
 * Recorta a parte do eixo que vai de um quilômetro a outro.
 *
 * Os vértices intermediários são preservados: um eixo com curva recortado só
 * pelas pontas viraria uma reta que corta fora da pista. A ordem do resultado
 * acompanha a ordem pedida, para o trecho ser desenhado no sentido em que foi
 * apontado.
 *
 * Devolve `null` quando o intervalo pedido não encosta no eixo. Um trecho que
 * só invade parcialmente é recortado no que existe — a parte de dentro é real
 * — em vez de sumir por inteiro.
 */
export function recortarEixoPorKm(
  eixo: EixoDaObra,
  kmA: number,
  kmB: number,
): Coordenada[] | null {
  if (!Number.isFinite(kmA) || !Number.isFinite(kmB)) {
    return null;
  }
  const menorDoEixo = Math.min(eixo.kmInicial, eixo.kmFinal);
  const maiorDoEixo = Math.max(eixo.kmInicial, eixo.kmFinal);
  const menorPedido = Math.min(kmA, kmB);
  const maiorPedido = Math.max(kmA, kmB);
  if (maiorPedido < menorDoEixo || menorPedido > maiorDoEixo) {
    return null;
  }

  const percurso = percorrer(eixo.coordenadas);
  if (percurso.comprimento <= 0) {
    return null;
  }

  const distanciaDoKm = (km: number): number => {
    const preso = Math.min(maiorDoEixo, Math.max(menorDoEixo, km));
    const fracao =
      (preso - eixo.kmInicial) / (eixo.kmFinal - eixo.kmInicial);
    return fracao * percurso.comprimento;
  };

  const inicio = distanciaDoKm(kmA);
  const fim = distanciaDoKm(kmB);
  const menorDistancia = Math.min(inicio, fim);
  const maiorDistancia = Math.max(inicio, fim);

  const recorte: Coordenada[] = [
    pontoNaDistancia(eixo.coordenadas, percurso, menorDistancia),
  ];
  for (let i = 0; i < eixo.coordenadas.length; i += 1) {
    const distancia = percurso.acumulado[i];
    if (distancia > menorDistancia && distancia < maiorDistancia) {
      recorte.push(eixo.coordenadas[i]);
    }
  }
  recorte.push(
    pontoNaDistancia(eixo.coordenadas, percurso, maiorDistancia),
  );

  // Um apontamento pontual — km inicial igual ao final — não é uma linha. Ele
  // vira um segmento de comprimento zero, que o mapa desenha como marca no
  // ponto em vez de descartar o registro.
  return inicio <= fim ? recorte : [...recorte].reverse();
}

/**
 * O quilômetro de um ponto qualquer, projetado sobre o eixo.
 *
 * É a volta do caminho: arrastar um extremo no mapa tem de virar quilômetro no
 * apontamento, senão a correção no mapa não seria correção coisa nenhuma — o
 * traço mudaria e o RDO seguiria afirmando o quilômetro velho.
 *
 * O ponto é projetado no segmento mais próximo do eixo, e é essa projeção que
 * vale. Quem arrasta o extremo raramente o solta exatamente sobre o traço, e
 * exigir precisão de pixel para aceitar a correção seria cobrar do dedo o que a
 * régua já sabe fazer.
 *
 * A conta é a mesma do desenho, ao contrário: distância acumulada até a
 * projeção, dividida pelo comprimento, mapeada na amplitude quilométrica.
 */
export function kmDoPonto(
  eixo: EixoDaObra,
  ponto: Coordenada,
): number | null {
  const percurso = percorrer(eixo.coordenadas);
  if (percurso.comprimento <= 0) {
    return null;
  }

  let melhorDistancia = Number.POSITIVE_INFINITY;
  let melhorAoLongo = 0;
  for (let i = 1; i < eixo.coordenadas.length; i += 1) {
    const a = eixo.coordenadas[i - 1];
    const b = eixo.coordenadas[i];
    const dx = b[0] - a[0];
    const dy = b[1] - a[1];
    const denominador = dx * dx + dy * dy;
    /*
     * A projeção é feita em graus, e não sobre a esfera. Num segmento de eixo
     * — dezenas de metros a poucos quilômetros — a diferença entre as duas é
     * muito menor que a mão de quem arrasta o ponto. Já a distância AO LONGO
     * do eixo, que é a que vira quilômetro, continua medida em Haversine.
     */
    const fracao =
      denominador <= 0
        ? 0
        : Math.min(
            1,
            Math.max(
              0,
              ((ponto[0] - a[0]) * dx + (ponto[1] - a[1]) * dy) / denominador,
            ),
          );
    const projetado: Coordenada = [a[0] + dx * fracao, a[1] + dy * fracao];
    const afastamento = distanciaM(ponto, projetado);
    if (afastamento < melhorDistancia) {
      melhorDistancia = afastamento;
      melhorAoLongo =
        percurso.acumulado[i - 1] + distanciaM(a, projetado);
    }
  }

  const proporcao = melhorAoLongo / percurso.comprimento;
  return (
    eixo.kmInicial + proporcao * (eixo.kmFinal - eixo.kmInicial)
  );
}

/**
 * A interdição declarada na Identificação de um RDO deste aparelho.
 *
 * <p>É a mesma linha reserva que o servidor deriva: quando nenhum serviço do
 * RDO declara quilômetro, o trecho interditado é o único km do dia. O
 * servidor a desenha na leitura — mas a linha derivada nunca entra no cache
 * de geometrias do aparelho, então sem rede ela simplesmente não existia, e o
 * RDO preenchido em campo ficava invisível no mapa até subir e voltar.
 */
export interface InterdicaoLocal {
  rdoId: string;
  numeroRdo: string | null;
  /** `YYYY-MM-DD` do RDO — é ela que o filtro de dia recorta. */
  data: string | null;
  kmInicial: number;
  kmFinal: number;
}

/**
 * Um trecho só se apoia no eixo quando declara os dois quilômetros.
 * Um extremo isolado descreveria um ponto, não um trecho percorrido.
 */
function posicionavel(segmento: SegmentoTrecho): boolean {
  return (
    typeof segmento.kmInicial === "number" &&
    Number.isFinite(segmento.kmInicial) &&
    typeof segmento.kmFinal === "number" &&
    Number.isFinite(segmento.kmFinal)
  );
}

/**
 * Traduz os apontamentos do RDO em linhas apoiadas no eixo.
 *
 * O que o mapa já mostra fica de fora — o desenho feito à mão, que é a posição
 * declarada por quem estava lá, e a linha que a própria API já derivou.
 * Sobrepor a elas uma segunda linha mostraria o mesmo trabalho duas vezes, em
 * lugares levemente diferentes.
 *
 * A feição derivada carrega a mesma categoria `TRECHO` das desenhadas, para
 * herdar cor, legenda e filtro sem exceção nenhuma, e a vigência recebe a data
 * do apontamento — é ela que faz o recorte por dia funcionar no mapa.
 */
export function feicoesApoiadasNoEixo(
  eixo: EixoDaObra,
  segmentos: readonly SegmentoTrecho[],
  rdosJaNoMapa: ReadonlySet<string> = new Set(),
): OperationalFeature[] {
  const feicoes: OperationalFeature[] = [];
  for (const segmento of segmentos) {
    if (segmento.origem === "PROGRAMACAO" || !posicionavel(segmento)) {
      continue;
    }
    if (segmento.rdoId && rdosJaNoMapa.has(segmento.rdoId)) {
      continue;
    }
    const recorte = recortarEixoPorKm(
      eixo,
      segmento.kmInicial as number,
      segmento.kmFinal as number,
    );
    if (!recorte) {
      continue;
    }
    feicoes.push({
      type: "Feature",
      id: `eixo:${segmento.id}`,
      geometry: { type: "LineString", coordinates: recorte },
      properties: {
        categoria: "TRECHO",
        [PROPRIEDADE_DERIVADA]: true,
        eixoId: eixo.id,
        objetoTipo: "RDO",
        objetoId: segmento.rdoId,
        // A linha de execução de onde o quilômetro veio. É por ela que uma
        // correção feita no mapa acha o apontamento para reescrever — e é o
        // mesmo nome que a API usa na feição que ela deriva.
        execucaoId: segmento.id,
        servico: segmento.servicoNome,
        numeroRdo: segmento.numeroRdo,
        sentido: segmento.sentido,
        faixa: segmento.faixa,
        kmInicial: segmento.kmInicial,
        kmFinal: segmento.kmFinal,
        estado: estadoDoSegmento(segmento),
        fonte: "APONTAMENTO_RDO",
        // A vigência do apontamento é o dia em que o trabalho aconteceu, e é
        // por ela que o filtro de dia do mapa recorta.
        validoDesde: segmento.data,
        validoAte: null,
      },
    });
  }
  return feicoes;
}

/**
 * RDOs cujos serviços declaram os dois quilômetros.
 *
 * <p>É a régua da regra reserva, a mesma do servidor: a interdição só desenha
 * quando nenhum serviço do RDO tem km próprio — com serviço posicionável, a
 * linha fina do serviço fala pelo dia, e a interdição por cima mostraria o
 * mesmo trabalho duas vezes.
 */
function rdosComServicoPosicionavel(
  segmentos: readonly SegmentoTrecho[],
): Set<string> {
  const rdos = new Set<string>();
  for (const segmento of segmentos) {
    if (
      segmento.origem === "EXECUCAO_SERVICO" &&
      segmento.rdoId &&
      posicionavel(segmento)
    ) {
      rdos.add(segmento.rdoId);
    }
  }
  return rdos;
}

/** As interdições que a derivação local deve tentar desenhar. */
function interdicoesQueDesenham(
  interdicoes: readonly InterdicaoLocal[],
  segmentos: readonly SegmentoTrecho[],
  jaNoMapa: ReadonlySet<string>,
): InterdicaoLocal[] {
  if (interdicoes.length === 0) return [];
  const comServico = rdosComServicoPosicionavel(segmentos);
  const vistas = new Set<string>();
  return interdicoes.filter((interdicao) => {
    if (!interdicao.rdoId || vistas.has(interdicao.rdoId)) return false;
    vistas.add(interdicao.rdoId);
    return (
      !jaNoMapa.has(interdicao.rdoId) && !comServico.has(interdicao.rdoId)
    );
  });
}

/** A linha da interdição, com a mesma identidade que o servidor emite. */
function feicaoDeInterdicao(
  eixo: EixoDaObra,
  interdicao: InterdicaoLocal,
  recorte: readonly Coordenada[],
): OperationalFeature {
  return {
    type: "Feature",
    // A mesma identidade da derivação do servidor: a lixeira de silêncio fala
    // dela pelo mesmo nome, esteja a linha vindo de lá ou daqui.
    id: `eixo:rdo:${interdicao.rdoId}`,
    geometry: { type: "LineString", coordinates: [...recorte] },
    properties: {
      categoria: "TRECHO",
      [PROPRIEDADE_DERIVADA]: true,
      eixoId: eixo.id,
      objetoTipo: "RDO",
      objetoId: interdicao.rdoId,
      // Sem execucaoId de propósito: não há linha de serviço para onde levar
      // uma correção de km — é o que desliga o lápis no balão.
      numeroRdo: interdicao.numeroRdo,
      kmInicial: interdicao.kmInicial,
      kmFinal: interdicao.kmFinal,
      fonte: "APONTAMENTO_RDO",
      validoDesde: interdicao.data,
      validoAte: null,
    },
  };
}

/**
 * RDOs que o mapa já mostra — desenhados à mão ou derivados pelo servidor.
 *
 * A mesma derivação roda na API, que é onde ela pertence: assim qualquer
 * consumidor do mapa vê o trecho, e não só esta tela. O que sobra para o
 * aparelho é o apontamento que ainda não subiu — o RDO preenchido em campo,
 * que o servidor não tem como conhecer. Contar as duas origens aqui é o que
 * impede a mesma linha de aparecer duas vezes, levemente deslocada.
 *
 * <p>O RDO cuja linha foi silenciada entra nesta mesma conta, vindo da
 * resposta do servidor. Ele não está desenhado, mas também não é um RDO que
 * o servidor desconhece: derivá-lo de novo aqui desfaria o silêncio na tela
 * de quem tem os apontamentos no aparelho — que é quase todo mundo que abre
 * o mapa da obra.
 */
export function rdosJaNoMapa(
  collection: OperationalFeatureCollection,
): Set<string> {
  const rdos = new Set<string>();
  for (const feature of collection.features) {
    if (feature.properties.categoria !== "TRECHO") {
      continue;
    }
    const objetoTipo = feature.properties.objetoTipo;
    const objetoId = feature.properties.objetoId;
    if (objetoTipo === "RDO" && typeof objetoId === "string" && objetoId) {
      rdos.add(objetoId);
    }
  }
  return rdos;
}

/**
 * Junta à coleção do mapa os trechos que o eixo sustenta.
 *
 * Devolve a MESMA referência quando não há nada a acrescentar: os dois mapas
 * remontam quando a coleção muda de identidade, e remontar sem mudança real
 * apaga o enquadramento de quem estava olhando.
 */
/**
 * O que o aparelho apontou por quilômetro e não conseguiu desenhar.
 *
 * <p>Espelha, com o que este aparelho tem, a mesma apuração que o servidor faz
 * com tudo o que ele tem. Existe pelo mesmo motivo que a derivação local
 * existe: sem rede, o servidor não fala, e o RDO preenchido em campo que ainda
 * não subiu só é conhecido aqui. Sem isto, a tela sem rede voltava a ficar
 * vazia em silêncio — exatamente o que esta mudança veio consertar.
 *
 * <p>Considera só o que a derivação local tentaria desenhar: o que já está no
 * mapa pelo servidor não está esperando nada.
 */
export function oQueEsperaARegua(
  collection: OperationalFeatureCollection,
  segmentos: readonly SegmentoTrecho[],
  rdosSilenciados: readonly string[] = [],
  interdicoes: readonly InterdicaoLocal[] = [],
): {
  motivo: "SEM_EIXO" | "FORA_DO_EIXO";
  total: number;
  kmInicial: number;
  kmFinal: number;
  primeiraData: string | null;
  ultimaData: string | null;
  eixoKmInicial: number | null;
  eixoKmFinal: number | null;
} | undefined {
  const eixo = lerEixoDaColecao(collection);
  const jaNoMapa = new Set([
    ...rdosJaNoMapa(collection),
    ...rdosSilenciados,
  ]);

  const esperando: { kmInicial: number; kmFinal: number; data: string | null }[] =
    [];
  for (const segmento of segmentos) {
    if (segmento.origem === "PROGRAMACAO" || !posicionavel(segmento)) continue;
    if (segmento.rdoId && jaNoMapa.has(segmento.rdoId)) continue;
    if (
      eixo &&
      recortarEixoPorKm(
        eixo,
        segmento.kmInicial as number,
        segmento.kmFinal as number,
      )
    ) {
      continue;
    }
    esperando.push({
      kmInicial: segmento.kmInicial as number,
      kmFinal: segmento.kmFinal as number,
      data: segmento.data,
    });
  }
  // A interdição espera a régua do mesmo jeito — é o caso do RDO que só
  // declarou o trecho interditado da Identificação, e era exatamente o que a
  // tela vazia escondia.
  for (const interdicao of interdicoesQueDesenham(
    interdicoes,
    segmentos,
    jaNoMapa,
  )) {
    if (
      eixo &&
      recortarEixoPorKm(eixo, interdicao.kmInicial, interdicao.kmFinal)
    ) {
      continue;
    }
    esperando.push({
      kmInicial: interdicao.kmInicial,
      kmFinal: interdicao.kmFinal,
      data: interdicao.data,
    });
  }
  if (esperando.length === 0) return undefined;

  const quilometros = esperando.flatMap((espera) => [
    espera.kmInicial,
    espera.kmFinal,
  ]);
  const datas = esperando
    .map((espera) => espera.data)
    .filter((data): data is string => Boolean(data))
    .sort();

  return {
    motivo: eixo ? "FORA_DO_EIXO" : "SEM_EIXO",
    total: esperando.length,
    kmInicial: Math.min(...quilometros),
    kmFinal: Math.max(...quilometros),
    primeiraData: datas[0] ?? null,
    ultimaData: datas.at(-1) ?? null,
    eixoKmInicial: eixo ? eixo.kmInicial : null,
    eixoKmFinal: eixo ? eixo.kmFinal : null,
  };
}

export function apoiarTrechosNoEixo(
  collection: OperationalFeatureCollection,
  segmentos: readonly SegmentoTrecho[],
  rdosSilenciados: readonly string[] = [],
  interdicoes: readonly InterdicaoLocal[] = [],
): OperationalFeatureCollection {
  const eixo = lerEixoDaColecao(collection);
  if (!eixo || (segmentos.length === 0 && interdicoes.length === 0)) {
    return collection;
  }
  const excluidos = new Set([
    ...rdosJaNoMapa(collection),
    ...rdosSilenciados,
  ]);
  const derivadas = feicoesApoiadasNoEixo(eixo, segmentos, excluidos);
  // A interdição vem depois dos serviços e cede a vez a eles: é a mesma linha
  // reserva que o servidor desenha quando o RDO só declarou o km da
  // Identificação.
  for (const interdicao of interdicoesQueDesenham(
    interdicoes,
    segmentos,
    excluidos,
  )) {
    const recorte = recortarEixoPorKm(
      eixo,
      interdicao.kmInicial,
      interdicao.kmFinal,
    );
    if (recorte) {
      derivadas.push(feicaoDeInterdicao(eixo, interdicao, recorte));
    }
  }
  if (derivadas.length === 0) {
    return collection;
  }
  return {
    type: "FeatureCollection",
    features: [...collection.features, ...derivadas],
  };
}
