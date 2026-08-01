/**
 * Trecho em marcação, antes de virar geometria gravada.
 *
 * Vive fora do componente de mapa de propósito: é o mesmo dado que o
 * formulário de cadastro edita e que a linha desenhada representa. Quando ele
 * morava dentro do mapa, qualquer coisa que desligasse o desenho apagava o
 * marco de início da tela sem que ninguém tivesse desistido dele.
 */

export interface PontoGeografico {
  lat: number;
  lng: number;
}

/** Os dois extremos que descrevem um trecho de rodovia. */
export type ExtremoDoTrecho = "INICIO" | "FIM";

export interface RascunhoDoTrecho {
  inicio: PontoGeografico | null;
  fim: PontoGeografico | null;
}

export const RASCUNHO_VAZIO: RascunhoDoTrecho = Object.freeze({
  inicio: null,
  fim: null,
});
