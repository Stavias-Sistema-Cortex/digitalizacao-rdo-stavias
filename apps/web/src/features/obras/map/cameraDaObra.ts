/**
 * Enquadramento comum aos dois mapas da obra.
 *
 * O painel vetorial e o Leaflet desenham a mesma obra por meios diferentes, e
 * quem lê os dois lado a lado quer que eles olhem para o mesmo lugar. O que
 * viaja entre eles é só isto: para onde a câmera aponta e o quanto se aproxima.
 * Inclinação e rotação ficam de fora porque o Leaflet não as tem — pedir que
 * ele as acompanhe seria pedir o que ele não faz.
 */
export interface CameraDaObra {
  readonly longitude: number;
  readonly latitude: number;
  readonly zoom: number;
}

/*
 * Tolerâncias de comparação.
 *
 * Dois mapas espelhados jamais param no mesmo número: cada um arredonda o
 * centro pelo seu próprio tamanho em pixels, e o que volta nunca é idêntico ao
 * que foi. Sem uma folga, cada aplicação geraria um movimento novo, que geraria
 * outra aplicação — o par entraria em oscilação e nenhum dos dois ficaria
 * parado.
 *
 * A folga é menor que um pixel no zoom em que a obra é lida, então ela corta o
 * eco sem apagar movimento que a pessoa realmente fez.
 */
const TOLERANCIA_DE_GRAU = 1e-6;
const TOLERANCIA_DE_ZOOM = 0.01;

export function mesmaCamera(
  esquerda: CameraDaObra | null | undefined,
  direita: CameraDaObra | null | undefined,
): boolean {
  if (!esquerda || !direita) {
    return esquerda === direita;
  }
  return (
    Math.abs(esquerda.longitude - direita.longitude) < TOLERANCIA_DE_GRAU &&
    Math.abs(esquerda.latitude - direita.latitude) < TOLERANCIA_DE_GRAU &&
    Math.abs(esquerda.zoom - direita.zoom) < TOLERANCIA_DE_ZOOM
  );
}

/** Coordenada utilizável: sem isso o espelho arrasta o outro mapa para o nada. */
export function cameraUtilizavel(
  candidata: CameraDaObra | null | undefined,
): candidata is CameraDaObra {
  return Boolean(
    candidata &&
      Number.isFinite(candidata.longitude) &&
      Number.isFinite(candidata.latitude) &&
      Number.isFinite(candidata.zoom) &&
      Math.abs(candidata.latitude) <= 90 &&
      Math.abs(candidata.longitude) <= 180,
  );
}
