/**
 * Cor das camadas geoespaciais persistidas em {@code obra_geometria}.
 *
 * A paleta não vive aqui: cada categoria aponta para um token declarado em
 * `index.css`, e o valor é lido do documento em tempo de execução. Trocar o
 * tema muda os dois mapas e o esquemático juntos, sem que nenhuma cor precise
 * ser repetida em código.
 */
const TOKEN_POR_CATEGORIA: Readonly<Record<string, string>> = Object.freeze({
  LOCALIZACAO_OBRA: "--map-localizacao-obra",
  PERIMETRO_OBRA: "--map-perimetro-obra",
  TRECHO: "--map-trecho",
  PONTO_OPERACIONAL: "--map-ponto-operacional",
  FRENTE_TRABALHO: "--map-frente-trabalho",
  EQUIPAMENTO: "--map-equipamento",
  EVENTO: "--map-evento",
  RDO: "--map-rdo",
  OCORRENCIA: "--map-ocorrencia",
  PROGRAMACAO: "--map-programacao",
});

const TOKEN_PADRAO = "--color-brand-teal";

/**
 * Valores usados quando não há documento — testes em ambiente Node e a
 * primeira pintura antes de a folha de estilo resolver. São os mesmos tokens
 * de `index.css`; qualquer divergência aparece no teste de política visual.
 */
const FALLBACK: Readonly<Record<string, string>> = Object.freeze({
  "--map-localizacao-obra": "#18211f",
  "--map-perimetro-obra": "#2f6f68",
  "--map-trecho": "#f2c300",
  "--map-ponto-operacional": "#0c2623",
  "--map-frente-trabalho": "#a43a3a",
  "--map-equipamento": "#313a37",
  "--map-evento": "#13332f",
  "--map-rdo": "#2f6f68",
  "--map-ocorrencia": "#a43a3a",
  "--map-programacao": "#66706b",
  "--color-brand-teal": "#0c2623",
  "--color-ink": "#18211f",
  "--color-brand-yellow": "#f2c300",
  "--color-danger": "#a43a3a",
  "--color-surface": "#f7f9f7",
  "--color-border-strong": "#bccac5",
});

const cache = new Map<string, string>();

function valorDoToken(token: string): string {
  const memorizado = cache.get(token);
  if (memorizado) {
    return memorizado;
  }
  let valor = "";
  if (typeof document !== "undefined") {
    valor = getComputedStyle(document.documentElement)
      .getPropertyValue(token)
      .trim();
  }
  const resolvido = valor || FALLBACK[token] || FALLBACK[TOKEN_PADRAO];
  if (valor) {
    cache.set(token, resolvido);
  }
  return resolvido;
}

/** Descarta as cores memorizadas, para quando o tema mudar em tempo de execução. */
export function limparCacheDeCores(): void {
  cache.clear();
}

/**
 * Resolve um token arbitrário do tema, com o mesmo cache e fallback das
 * categorias. É o caminho para qualquer cor desenhada por JavaScript — pinos
 * de rascunho, contorno de linha — continuar saindo de `index.css`.
 */
export function corDoToken(token: string): string {
  return valorDoToken(token);
}

export function tokenDaCategoria(categoria: unknown): string {
  return typeof categoria === "string" && categoria in TOKEN_POR_CATEGORIA
    ? TOKEN_POR_CATEGORIA[categoria]
    : TOKEN_PADRAO;
}

export function corDaCategoria(categoria: unknown): string {
  return valorDoToken(tokenDaCategoria(categoria));
}

/**
 * Nome da camada como se escreve, não como está gravado.
 *
 * A categoria é persistida em caixa alta com sublinhado
 * (`PONTO_OPERACIONAL`), o que serve ao banco e não ao texto. Passar tudo
 * para minúscula resolvia a gritaria e criava outro erro: "ponto operacional"
 * abrindo legenda e balão do mapa em caixa baixa. Caixa de sentença é a forma
 * correta em português para rótulo de interface.
 */
export function rotuloDaCategoria(categoria: unknown): string {
  return rotuloLegivel(categoria, "Camada operacional");
}

/** Origem do dado (`GESTAO_MAPA`, `CAPTURA_CAMPO`) escrita como texto. */
export function rotuloDaFonte(fonte: unknown): string {
  return rotuloLegivel(fonte, "Origem não declarada");
}

/**
 * Converte um valor persistido em caixa alta para caixa de sentença.
 *
 * Só a primeira letra sobe: nomes de camada e de origem são substantivos
 * comuns, e capitalizar cada palavra ("Ponto Operacional") é convenção de
 * inglês, não de português.
 */
function rotuloLegivel(valor: unknown, padrao: string): string {
  if (typeof valor !== "string" || !valor.trim()) {
    return padrao;
  }
  const texto = valor.replaceAll("_", " ").trim().toLowerCase();
  return texto.charAt(0).toUpperCase() + texto.slice(1);
}

/**
 * Expressão `match` do MapLibre e do Mapbox, com as cores já resolvidas a
 * partir dos mesmos tokens.
 */
export function categoryColorExpression(): unknown[] {
  return [
    "match",
    ["get", "categoria"],
    ...Object.keys(TOKEN_POR_CATEGORIA).flatMap((categoria) => [
      categoria,
      corDaCategoria(categoria),
    ]),
    valorDoToken(TOKEN_PADRAO),
  ];
}
