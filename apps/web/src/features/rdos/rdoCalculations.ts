import type {
  ControleGeometricoDraft,
  MaterialDraft,
  NumericInput,
} from "./rdo.types";

export interface ControleGeometricoCalculo {
  espessuraMediaCm: number | null;
  areaM2: number | null;
  volumeM3: number | null;
  massaTonelada: number | null;
}

function asNumber(value: NumericInput): number | null {
  return typeof value === "number" && Number.isFinite(value)
    ? value
    : null;
}

function round3(value: number): number {
  return Math.round((value + Number.EPSILON) * 1000) / 1000;
}

export function calcularSobraMaterial(
  material: MaterialDraft,
): number | null {
  const sobraInformada = asNumber(material.quantidadeSobra);
  if (sobraInformada !== null) {
    return round3(sobraInformada);
  }

  const usinada = asNumber(material.quantidadeUsinada);
  const aplicada = asNumber(material.quantidadeAplicada);
  if (usinada === null || aplicada === null) {
    return null;
  }

  return round3(usinada - aplicada);
}

export function calcularControleGeometrico(
  item: ControleGeometricoDraft,
): ControleGeometricoCalculo {
  const espessuras = [
    asNumber(item.espessura1Cm),
    asNumber(item.espessura2Cm),
    asNumber(item.espessura3Cm),
  ].filter((value): value is number => value !== null);

  const espessuraMediaCm =
    espessuras.length === 0
      ? null
      : round3(
          espessuras.reduce((sum, value) => sum + value, 0) /
            espessuras.length,
        );

  const comprimentoM = asNumber(item.comprimentoM);
  const larguraM = asNumber(item.larguraM);
  const areaM2 =
    comprimentoM === null || larguraM === null
      ? null
      : round3(comprimentoM * larguraM);

  const volumeM3 =
    areaM2 === null || espessuraMediaCm === null
      ? null
      : round3(areaM2 * (espessuraMediaCm / 100));

  const densidade = asNumber(item.densidade);
  const massaTonelada =
    volumeM3 === null || densidade === null
      ? null
      : round3(volumeM3 * densidade);

  return {
    espessuraMediaCm,
    areaM2,
    volumeM3,
    massaTonelada,
  };
}

export function formatCalculatedNumber(
  value: number | null,
): string {
  return value === null
    ? "Em branco"
    : new Intl.NumberFormat("pt-BR", {
        maximumFractionDigits: 3,
      }).format(value);
}
