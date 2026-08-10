import type { LocalRdoRecord } from "../../lib/db/db.types";
import { extensionMeters } from "./rdoCalculations";
import { numeroDigitado } from "../../lib/numeros/numeroDigitado";

/**
 * Os trechos que um RDO declara, e quanto eles medem.
 *
 * <p>Mora fora da tela porque tem três leitores — o cartão da lista, o resumo
 * do registro e o mapa — e porque a regra que ele carrega já se perdeu uma vez
 * numa cópia.
 *
 * <p>O quilômetro vinha de {@code controlesGeometricos}. Essa etapa saiu do
 * RDO justamente para o quilômetro existir num lugar só, e passou a morar na
 * linha de execução — mas a contagem ficou apontando para o bloco vazio. O
 * efeito era um RDO sincronizado, com o trecho preenchido e visível na tela de
 * edição, aparecendo na lista como "Trechos 0 · Extensão 0 m".
 */

function asObject(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function asArray(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.map(asObject).filter((item) => Object.keys(item).length > 0)
    : [];
}

function asText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

/** Os dois vocabulários do mesmo trecho, na ordem em que valem. */
export function extremosDoTrecho(
  item: Record<string, unknown>,
): { inicio: string; fim: string } {
  return {
    inicio: asText(item.trechoInicial) || asText(item.kmInicial),
    fim: asText(item.trechoFinal) || asText(item.kmFinal),
  };
}

/**
 * As linhas do RDO que declaram um trecho.
 *
 * <p>O bloco antigo continua sendo lido: RDO gravado antes da mudança ainda o
 * carrega no payload, e o histórico não se reescreve por causa de uma troca de
 * estrutura.
 */
export function trechosDoRdo(
  record: Pick<LocalRdoRecord, "payload">,
): Record<string, unknown>[] {
  const payload = asObject(record.payload);
  const execucoes = asArray(payload.servicosExecutados).filter((item) => {
    const { inicio, fim } = extremosDoTrecho(item);
    return Boolean(inicio || fim);
  });
  return [...execucoes, ...asArray(payload.controlesGeometricos)];
}

/**
 * Extensão declarada pelo RDO, em metros.
 *
 * <p>A conta é {@link extensionMeters}, a mesma do editor, e não uma cópia: a
 * pista Sul tem quilometragem decrescente — começa no 400 e termina no 398 — e
 * a subtração ingênua devolvia zero justamente ali. Distância entre dois pontos
 * não tem sinal; quem tem sentido é a pista.
 *
 * <p>Comprimento já medido prevalece sobre o cálculo pelo quilômetro: quem
 * mediu em campo sabe mais que a diferença entre duas estacas.
 */
export function extensaoDoRdoM(
  record: Pick<LocalRdoRecord, "payload">,
): number {
  return trechosDoRdo(record).reduce((total, item) => {
    const medido = numeroDigitado(
      typeof item.comprimentoM === "number" || typeof item.comprimentoM === "string"
        ? item.comprimentoM
        : "",
    );
    if (medido !== null) return total + medido;
    const { inicio, fim } = extremosDoTrecho(item);
    return total + (extensionMeters(inicio, fim) ?? 0);
  }, 0);
}
