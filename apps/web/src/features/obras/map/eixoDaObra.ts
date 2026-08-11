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
 * O que já foi desenhado à mão fica de fora: o desenho é a posição declarada
 * por quem estava lá, e sobrepor a ela uma linha derivada mostraria o mesmo
 * trabalho duas vezes, em lugares levemente diferentes.
 *
 * A feição derivada carrega a mesma categoria `TRECHO` das desenhadas, para
 * herdar cor, legenda e filtro sem exceção nenhuma, e a vigência recebe a data
 * do apontamento — é ela que faz o recorte por dia funcionar no mapa.
 */
export function feicoesApoiadasNoEixo(
  eixo: EixoDaObra,
  segmentos: readonly SegmentoTrecho[],
  rdosJaDesenhados: ReadonlySet<string> = new Set(),
): OperationalFeature[] {
  const feicoes: OperationalFeature[] = [];
  for (const segmento of segmentos) {
    if (segmento.origem === "PROGRAMACAO" || !posicionavel(segmento)) {
      continue;
    }
    if (segmento.rdoId && rdosJaDesenhados.has(segmento.rdoId)) {
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

/** RDOs que já têm desenho próprio no mapa e não precisam do eixo. */
export function rdosComDesenhoProprio(
  collection: OperationalFeatureCollection,
): Set<string> {
  const rdos = new Set<string>();
  for (const feature of collection.features) {
    if (
      feature.properties.categoria !== "TRECHO" ||
      feature.properties[PROPRIEDADE_DERIVADA] === true
    ) {
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
export function apoiarTrechosNoEixo(
  collection: OperationalFeatureCollection,
  segmentos: readonly SegmentoTrecho[],
): OperationalFeatureCollection {
  const eixo = lerEixoDaColecao(collection);
  if (!eixo || segmentos.length === 0) {
    return collection;
  }
  const derivadas = feicoesApoiadasNoEixo(
    eixo,
    segmentos,
    rdosComDesenhoProprio(collection),
  );
  if (derivadas.length === 0) {
    return collection;
  }
  return {
    type: "FeatureCollection",
    features: [...collection.features, ...derivadas],
  };
}
