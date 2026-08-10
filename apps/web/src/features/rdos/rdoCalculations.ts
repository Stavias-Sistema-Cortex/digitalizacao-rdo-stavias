import type {
  ControleGeometricoDraft,
  MaterialDraft,
  NumericInput,
} from "./rdo.types";
import { quilometroDigitado } from "../../lib/numeros/quilometroDigitado";

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

/**
 * Lê km aceitando vírgula, que é como se digita em campo.
 *
 * <p>Campo em branco é ausência, não o km zero. `Number("")` devolve 0, e com
 * isso um trecho pela metade — só o km final preenchido — virava uma extensão
 * de quatrocentos quilômetros na tela, medida contra uma origem que ninguém
 * informou.
 *
 * <p>Quilômetro tem leitor próprio, e não o dos demais campos. O leitor geral
 * trata ponto como milhar — `1.234` é mil duzentos e trinta e quatro —, e essa
 * regra, aplicada a `206.822`, devolvia duzentos e seis mil. A extensão do RDO
 * saía em centenas de milhares de metros e o trecho ia parar fora do mapa, tudo
 * a partir de um km escrito exatamente como a base inteira o guarda.
 */
export function parseKm(value: string): number | null {
  return quilometroDigitado(value);
}

/**
 * A extensão entre dois km, em metros, sem se importar com o sentido.
 *
 * <p>Antes esta conta devolvia "em branco" sempre que o km final fosse menor
 * que o inicial — e é exatamente assim que se aponta a pista Sul, cuja
 * quilometragem é decrescente: começa no km 400 e termina no 398. O trecho
 * existia, tinha dois quilômetros, e a tela dizia que não havia extensão
 * nenhuma. Distância entre dois pontos não tem sinal; quem tem sentido é a
 * pista, e isso é assunto do campo Sentido, não desta subtração.
 */
export function extensionMeters(
  start: string,
  end: string,
): number | null {
  const startKm = parseKm(start);
  const endKm = parseKm(end);

  if (startKm === null || endKm === null) {
    return null;
  }

  return Math.round(Math.abs(endKm - startKm) * 1000 * 1000) / 1000;
}

export interface MedidasDoServico {
  comprimentoM: number | null;
  areaM2: number | null;
  volumeM3: number | null;
}

/**
 * Comprimento, área e volume de um serviço executado.
 *
 * <p>São conta, não campo: gravar o resultado ao lado das parcelas cria duas
 * versões da mesma verdade, que divergem no primeiro acerto de uma delas.
 * Cada uma só aparece quando as parcelas que a compõem existem — sem largura
 * não há área, e área ausente não é área zero.
 *
 * <p>A espessura é lida em metros. Ela morava em centímetros porque é assim
 * que se fala dela em campo — "cinco centímetros de capa" —, mas era a única
 * medida do serviço numa unidade diferente das outras duas, e a conversão
 * escondida no meio da conta de volume era um lugar a mais para errar por
 * cem. Quem digita continua digitando o que mede; o que mudou é o rótulo do
 * campo, que agora diz metros.
 */
export function medidasDoServico(item: {
  trechoInicial: string;
  trechoFinal: string;
  larguraM: NumericInput;
  espessuraM: NumericInput;
}): MedidasDoServico {
  const comprimentoM = extensionMeters(
    item.trechoInicial,
    item.trechoFinal,
  );
  const larguraM = asNumber(item.larguraM);
  const espessuraM = asNumber(item.espessuraM);
  const areaM2 =
    comprimentoM === null || larguraM === null
      ? null
      : round3(comprimentoM * larguraM);
  const volumeM3 =
    areaM2 === null || espessuraM === null
      ? null
      : round3(areaM2 * espessuraM);
  return { comprimentoM, areaM2, volumeM3 };
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
