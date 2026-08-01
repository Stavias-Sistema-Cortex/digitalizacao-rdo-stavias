import type {
  OperationalFeature,
  OperationalFeatureCollection,
} from "../obras/map/mapGeometry";
import type { ChartPeriod } from "./progressSeries";

/**
 * Recorte da evolução geoespacial da obra pela janela do gráfico da Home.
 *
 * O card do empreendimento já filtra o avanço físico por 3, 6 ou 12 meses; o
 * mapa ao lado precisa contar a mesma história — mostrar tudo enquanto o
 * gráfico mostra um semestre faria os dois painéis discordarem na mesma tela.
 *
 * A janela é um intervalo, não um dia: entra a geometria que esteve vigente em
 * qualquer momento do período, porque a evolução é o que aconteceu ao longo
 * dele, inclusive o trecho que começou antes e ainda vale.
 */
export interface JanelaDeEvolucao {
  /** Primeiro dia considerado, ISO `YYYY-MM-DD`. Nulo não limita o passado. */
  inicio: string | null;
}

const MESES_POR_PERIODO: Readonly<Record<ChartPeriod, number | null>> = {
  "3M": 3,
  "6M": 6,
  "12M": 12,
  ALL: null,
};

export function janelaDoPeriodo(
  period: ChartPeriod,
  hoje: Date = new Date(),
): JanelaDeEvolucao {
  const meses = MESES_POR_PERIODO[period];
  if (meses === null) {
    return { inicio: null };
  }
  const inicio = new Date(hoje.getTime());
  inicio.setUTCMonth(inicio.getUTCMonth() - meses);
  return { inicio: inicio.toISOString().slice(0, 10) };
}

function textoDaPropriedade(
  feature: OperationalFeature,
  chave: string,
): string {
  const valor = feature.properties[chave];
  return typeof valor === "string" ? valor : "";
}

/**
 * Diz se a geometria participou do período observado.
 *
 * Geometria sem `validoDesde` legível é tratada como sempre presente — é o
 * caso do ponto da própria obra, que não tem vigência e não deve sumir do
 * mapa por causa do recorte do gráfico.
 */
export function participouDaJanela(
  feature: OperationalFeature,
  janela: JanelaDeEvolucao,
): boolean {
  if (!janela.inicio) return true;
  const ate = textoDaPropriedade(feature, "validoAte").slice(0, 10);
  // O que segue vigente participou de qualquer janela que alcance o presente;
  // só o que foi encerrado antes do início do período fica de fora.
  return !ate || ate >= janela.inicio;
}

export function recortarEvolucao(
  collection: OperationalFeatureCollection,
  janela: JanelaDeEvolucao,
): OperationalFeatureCollection {
  if (!janela.inicio) {
    // Sem recorte pedido, a mesma referência: o mapa remonta quando a coleção
    // muda de identidade, e remontar à toa apaga o enquadramento.
    return collection;
  }
  return {
    type: "FeatureCollection",
    features: collection.features.filter((feature) =>
      participouDaJanela(feature, janela)),
  };
}
