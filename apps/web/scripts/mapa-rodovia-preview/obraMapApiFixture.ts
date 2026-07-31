/** Substitui `obraMapApi` no harness de verificação visual. */
import type {
  ObraMapData,
  LeituraMapaObra,
} from "../../src/features/obras/map/obraMapApi";
import { FEATURES_FIXTURE, OBRA_FIXTURE } from "./fixtures";

export type { ObraMapData, LeituraMapaObra };

export function obraMapResponseFromApi(): ObraMapData {
  return { obra: OBRA_FIXTURE, features: FEATURES_FIXTURE };
}

export function buscarMapaObra(): Promise<ObraMapData> {
  return Promise.resolve({ obra: OBRA_FIXTURE, features: FEATURES_FIXTURE });
}

export function carregarMapaObra(): Promise<LeituraMapaObra> {
  return Promise.resolve({
    dados: { obra: OBRA_FIXTURE, features: FEATURES_FIXTURE },
    origem: "REDE",
    obtidoEm: "2026-03-03T18:30:00.000Z",
  });
}
