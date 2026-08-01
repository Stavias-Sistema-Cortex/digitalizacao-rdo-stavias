import type {
  OperationalFeature,
  OperationalFeatureCollection,
} from "./mapGeometry";

/**
 * Filtros das camadas do mapa da obra.
 *
 * O mapa desenhava tudo o que a leitura devolvia, sem nenhum recorte: as
 * marcações se sobrepunham e não havia como olhar um dia específico. Cada
 * geometria carrega vigência (`validoDesde`/`validoAte`), então a variação
 * diária já está no dado — faltava perguntar por ela.
 *
 * As funções são puras porque as duas metades do mapa (MapLibre e Leaflet)
 * precisam recortar exatamente igual; um recorte divergente mostraria obras
 * diferentes lado a lado.
 */

export interface FiltroDoMapa {
  /** Categorias que o operador desmarcou. Vazio significa mostrar todas. */
  categoriasOcultas: ReadonlySet<string>;
  /**
   * Dia observado, em ISO `YYYY-MM-DD`. Vazio não aplica recorte de tempo:
   * esconder histórico por conta própria seria omitir dado sem ser pedido.
   */
  data: string;
}

export const FILTRO_VAZIO: FiltroDoMapa = {
  categoriasOcultas: new Set<string>(),
  data: "",
};

function textoDaPropriedade(
  feature: OperationalFeature,
  chave: string,
): string {
  const valor = feature.properties[chave];
  return typeof valor === "string" ? valor : "";
}

export function categoriaDaFeature(feature: OperationalFeature): string {
  return textoDaPropriedade(feature, "categoria") || "SEM_CATEGORIA";
}

/** Categorias presentes na leitura, ordenadas para a legenda não dançar. */
export function categoriasDaColecao(
  collection: OperationalFeatureCollection,
): string[] {
  return [
    ...new Set(collection.features.map(categoriaDaFeature)),
  ].sort((esquerda, direita) => esquerda.localeCompare(direita, "pt-BR"));
}

/**
 * Diz se a geometria valia no dia observado.
 *
 * A comparação é feita no prefixo da data porque a vigência é gravada como
 * instante e o operador pensa em dia. Uma geometria sem `validoDesde` legível
 * é tratada como sempre vigente — é o caso do ponto da própria obra, que não
 * tem vigência e não deve sumir por causa de um filtro de tempo.
 */
export function vigenteEm(feature: OperationalFeature, data: string): boolean {
  if (!data) return true;
  const desde = textoDaPropriedade(feature, "validoDesde").slice(0, 10);
  const ate = textoDaPropriedade(feature, "validoAte").slice(0, 10);
  if (desde && desde > data) return false;
  // `validoAte` é o instante em que a geometria deixou de valer, então o
  // próprio dia do encerramento ainda conta como vigente.
  if (ate && ate < data) return false;
  return true;
}

export function filtrarColecao(
  collection: OperationalFeatureCollection,
  filtro: FiltroDoMapa,
): OperationalFeatureCollection {
  if (filtro.categoriasOcultas.size === 0 && !filtro.data) {
    // Sem recorte pedido, devolve a mesma referência: o mapa remonta quando a
    // coleção muda de identidade, e remontar à toa apaga o enquadramento.
    return collection;
  }
  return {
    type: "FeatureCollection",
    features: collection.features.filter(
      (feature) =>
        !filtro.categoriasOcultas.has(categoriaDaFeature(feature)) &&
        vigenteEm(feature, filtro.data),
    ),
  };
}

/**
 * Recorte por janela de evolução: o que participou do período.
 *
 * Diferente de {@link vigenteEm}, que responde "o que valia NESTE dia", aqui a
 * pergunta é "o que esteve vigente em ALGUM momento desde o início da
 * janela" — a evolução de um semestre inclui o trecho aberto antes e ainda
 * válido. Só fica de fora o que registra encerramento anterior ao início.
 *
 * Geometria sem `validoAte` legível é tratada como ainda vigente e participa
 * de qualquer janela — é o caso do ponto da própria obra, que não tem
 * vigência e não deve sumir por causa do recorte.
 */
export function recortarPorJanela(
  collection: OperationalFeatureCollection,
  inicio: string | null,
): OperationalFeatureCollection {
  if (!inicio) {
    // Sem recorte pedido, a mesma referência: o mapa remonta quando a coleção
    // muda de identidade, e remontar à toa apaga o enquadramento.
    return collection;
  }
  return {
    type: "FeatureCollection",
    features: collection.features.filter((feature) => {
      const valor = feature.properties.validoAte;
      const ate = typeof valor === "string" ? valor.slice(0, 10) : "";
      return !ate || ate >= inicio;
    }),
  };
}

export function alternarCategoria(
  ocultas: ReadonlySet<string>,
  categoria: string,
): Set<string> {
  const proximo = new Set(ocultas);
  if (proximo.has(categoria)) {
    proximo.delete(categoria);
  } else {
    proximo.add(categoria);
  }
  return proximo;
}
