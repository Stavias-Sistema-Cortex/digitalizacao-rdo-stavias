import type {
  OperationalFeature,
  OperationalFeatureCollection,
} from "./mapGeometry";

/**
 * Fase de execução de cada segmento do trecho.
 *
 * O trecho cresce um pouco por dia, e o mapa desenhava tudo com a mesma cor —
 * o traçado de hoje idêntico ao de três meses atrás. Quem olha o mapa no fim
 * do dia quer a pergunta oposta: o que avançou HOJE, o que é recente, o que já
 * assentou. Cada geometria carrega vigência (`validoDesde`/`validoAte`), então
 * a linha do tempo sempre esteve no dado; faltava traduzi-la em cor.
 *
 * As fases são quatro, e a régua é o dia de quem opera:
 * - HOJE: registrado no dia corrente — o avanço da jornada, em amarelo vivo;
 * - RECENTE: últimos sete dias — a frente da semana, em amarelo queimado;
 * - CONSOLIDADA: mais antigo que a semana — execução assentada, em teal;
 * - ENCERRADA: com `validoAte` — deixou de valer, cinza e tracejada.
 */
export type FaseDeExecucao =
  | "HOJE"
  | "RECENTE"
  | "CONSOLIDADA"
  | "ENCERRADA";

/** Nome da propriedade injetada nas feições; os dois mapas leem daqui. */
export const PROPRIEDADE_FASE = "faseExecucao";

/**
 * Categorias que representam execução linear no território. Ponto de obra,
 * equipamento e evento não têm "avanço" — colori-los por idade só confundiria.
 */
const CATEGORIAS_DE_EXECUCAO: ReadonlySet<string> = new Set([
  "TRECHO",
  "FRENTE_TRABALHO",
]);

const DIAS_DE_RECENCIA = 7;

/** Tokens de cor por fase — os valores vivem em `index.css`. */
export const TOKEN_POR_FASE: Readonly<Record<FaseDeExecucao, string>> =
  Object.freeze({
    HOJE: "--map-execucao-hoje",
    RECENTE: "--map-execucao-recente",
    CONSOLIDADA: "--map-execucao-consolidada",
    ENCERRADA: "--map-execucao-encerrada",
  });

export const ROTULO_POR_FASE: Readonly<Record<FaseDeExecucao, string>> =
  Object.freeze({
    HOJE: "Hoje",
    RECENTE: "Últimos 7 dias",
    CONSOLIDADA: "Consolidado",
    ENCERRADA: "Encerrado",
  });

/** Ordem fixa da legenda: do mais novo para o mais antigo, como se lê o dia. */
export const FASES_EM_ORDEM: readonly FaseDeExecucao[] = Object.freeze([
  "HOJE",
  "RECENTE",
  "CONSOLIDADA",
  "ENCERRADA",
]);

export function hojeIso(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Classifica pela vigência.
 *
 * Encerramento vence a idade: um segmento desenhado hoje e encerrado hoje está
 * encerrado — mostrá-lo como "avanço de hoje" afirmaria execução que foi
 * desfeita. Sem `validoDesde` legível a fase é CONSOLIDADA, porque idade
 * desconhecida não pode reivindicar o destaque do dia.
 */
export function faseDaVigencia(
  validoDesde: unknown,
  validoAte: unknown,
  hoje: string,
): FaseDeExecucao {
  const ate = typeof validoAte === "string" ? validoAte.slice(0, 10) : "";
  if (ate) {
    return "ENCERRADA";
  }
  const desde = typeof validoDesde === "string"
    ? validoDesde.slice(0, 10)
    : "";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(desde)) {
    return "CONSOLIDADA";
  }
  if (desde >= hoje) {
    return "HOJE";
  }
  const inicioDaJanela = new Date(`${hoje}T00:00:00Z`);
  inicioDaJanela.setUTCDate(inicioDaJanela.getUTCDate() - DIAS_DE_RECENCIA);
  return desde >= inicioDaJanela.toISOString().slice(0, 10)
    ? "RECENTE"
    : "CONSOLIDADA";
}

function categoriaDe(feature: OperationalFeature): string {
  const valor = feature.properties.categoria;
  return typeof valor === "string" ? valor : "";
}

/**
 * Injeta a fase nas feições de execução, deixando as demais intactas.
 *
 * Devolve a MESMA referência quando nada há para anotar: os dois mapas
 * remontam quando a coleção muda de identidade, e remontar sem mudança real
 * apaga o enquadramento de quem estava olhando.
 */
export function anotarFasesDeExecucao(
  collection: OperationalFeatureCollection,
  hoje: string = hojeIso(),
): OperationalFeatureCollection {
  if (!collection.features.some(
    (feature) => CATEGORIAS_DE_EXECUCAO.has(categoriaDe(feature)),
  )) {
    return collection;
  }
  return {
    type: "FeatureCollection",
    features: collection.features.map((feature) => {
      if (!CATEGORIAS_DE_EXECUCAO.has(categoriaDe(feature))) {
        return feature;
      }
      return {
        ...feature,
        properties: {
          ...feature.properties,
          [PROPRIEDADE_FASE]: faseDaVigencia(
            feature.properties.validoDesde,
            feature.properties.validoAte,
            hoje,
          ),
        },
      };
    }),
  };
}

export function faseDaFeature(
  feature: OperationalFeature,
): FaseDeExecucao | null {
  const valor = feature.properties[PROPRIEDADE_FASE];
  return valor === "HOJE" ||
      valor === "RECENTE" ||
      valor === "CONSOLIDADA" ||
      valor === "ENCERRADA"
    ? valor
    : null;
}

/** Fases presentes na coleção, na ordem da legenda. */
export function fasesPresentes(
  collection: OperationalFeatureCollection,
): FaseDeExecucao[] {
  const presentes = new Set<FaseDeExecucao>();
  for (const feature of collection.features) {
    const fase = faseDaFeature(feature);
    if (fase) {
      presentes.add(fase);
    }
  }
  return FASES_EM_ORDEM.filter((fase) => presentes.has(fase));
}

/**
 * O serviço que o segmento representa, se quem o registrou disse qual é.
 *
 * As propriedades são livres — a captura de campo e a gestão do mapa gravam
 * chaves diferentes conforme a época. Procurar nas conhecidas evita que a
 * informação exista no dado e não apareça na tela.
 */
export function servicoDaFeature(feature: OperationalFeature): string {
  for (const chave of ["servico", "descricao", "nome", "observacao"]) {
    const valor = feature.properties[chave];
    if (typeof valor === "string" && valor.trim()) {
      return valor.trim();
    }
  }
  return "";
}

/** Largura da linha por fase — o avanço de hoje é o mais grosso. */
export function pesoDaFase(fase: FaseDeExecucao | null): number {
  if (fase === "HOJE") return 8;
  if (fase === "RECENTE") return 6;
  if (fase === "ENCERRADA") return 3;
  return 5;
}
