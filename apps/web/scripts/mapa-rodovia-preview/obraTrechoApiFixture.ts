/** Substitui `obraTrechoApi` no harness de verificação visual. */
import type { LeituraTrecho } from "../../src/features/obras/trecho/obraTrechoApi";
import { PROJECAO_DESATIVADA_FIXTURE, PROJECAO_FIXTURE } from "./fixtures";

export type { LeituraTrecho };

export function trechoResponseFromApi() {
  return PROJECAO_FIXTURE;
}

export function buscarTrechoObra(obraId: string): Promise<LeituraTrecho> {
  return Promise.resolve({
    projecao: obraId.endsWith("desativada")
      ? PROJECAO_DESATIVADA_FIXTURE
      : PROJECAO_FIXTURE,
    origem: "REDE",
    obtidoEm: "2026-03-03T18:30:00.000Z",
  });
}

export function carregarTrechoDaObra(obra: {
  id: string;
}): Promise<LeituraTrecho> {
  return buscarTrechoObra(obra.id);
}
