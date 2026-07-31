/**
 * Paleta semântica das categorias geoespaciais persistidas em
 * {@code obra_geometria}. É a mesma lista do CHECK constraint do banco, para que
 * satélite e Leaflet leiam a camada com a mesma cor.
 */
export const CATEGORIA_CORES: Readonly<Record<string, string>> = Object.freeze({
  LOCALIZACAO_OBRA: "#fed203",
  PERIMETRO_OBRA: "#17a398",
  TRECHO: "#f7a531",
  PONTO_OPERACIONAL: "#0e5e57",
  FRENTE_TRABALHO: "#ef6f6c",
  EQUIPAMENTO: "#4e79a7",
  EVENTO: "#8e6bbf",
  RDO: "#35a853",
  OCORRENCIA: "#d9534f",
  PROGRAMACAO: "#6f7d75",
});

export const CATEGORIA_COR_PADRAO = "#0e5e57";

export function corDaCategoria(categoria: unknown): string {
  return typeof categoria === "string" && categoria in CATEGORIA_CORES
    ? CATEGORIA_CORES[categoria]
    : CATEGORIA_COR_PADRAO;
}

export function rotuloDaCategoria(categoria: unknown): string {
  return typeof categoria === "string"
    ? categoria.replaceAll("_", " ").toLowerCase()
    : "camada operacional";
}

/**
 * Expressão `match` do MapLibre/Mapbox derivada da mesma paleta, evitando que a
 * lista de cores exista duas vezes.
 */
export function categoryColorExpression(): unknown[] {
  return [
    "match",
    ["get", "categoria"],
    ...Object.entries(CATEGORIA_CORES).flat(),
    CATEGORIA_COR_PADRAO,
  ];
}
