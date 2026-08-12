/**
 * Os apontamentos de mão de obra que este aparelho já tem, lidos dos RDOs.
 *
 * <p>O rateio não pede nada a ninguém: ele lê os RDOs que a sincronização já
 * trouxe e conta quem esteve onde. Por isso a tela funciona sem rede — e por
 * isso ela precisa ser honesta sobre o que ainda não chegou.
 *
 * <p>Essa honestidade é o motivo de a leitura devolver mais do que a lista de
 * apontamentos. Um RDO pode estar no aparelho como <b>cabeçalho apenas</b>: a
 * reconciliação grava número, obra e data assim que os descobre, e busca o
 * conteúdo em passagens seguintes, quarenta por vez. Um cabeçalho sem conteúdo
 * não é um RDO sem ninguém — é um RDO que ainda não foi aberto. Contá-lo como
 * vazio faria a fatia de uma obra encolher sem que nada tivesse mudado em
 * campo, e o gestor tomaria essa queda por informação.
 */

import { listLocalRdos } from "../../../lib/db/rdoRepository";
import type { LocalRdoRecord } from "../../../lib/db/db.types";

import type { ApontamentoDeMaoDeObra } from "./rateioDeMaoDeObra";

export interface LeituraDeApontamentos {
  apontamentos: readonly ApontamentoDeMaoDeObra[];
  /** RDOs do período cujo conteúdo já está no aparelho. */
  rdosLidos: number;
  /**
   * RDOs do período que existem aqui só como cabeçalho.
   *
   * <p>É o número que a tela mostra quando avisa que o retrato ainda está
   * incompleto.
   */
  rdosSemConteudo: number;
  /** RDOs do período que trouxeram conteúdo e não tinham ninguém apontado. */
  rdosSemMaoDeObra: number;
}

interface FiltroDeApontamentos {
  inicio: string;
  fim: string;
  /** Quando presente, só estas obras entram. Vazio significa todas. */
  obraIds?: readonly string[];
}

function texto(valor: unknown): string {
  return typeof valor === "string" ? valor.trim() : "";
}

/**
 * O RDO conta para o rateio?
 *
 * <p>Apagado não conta: o dia dele foi desfeito, e mantê-lo no rateio faria
 * uma obra continuar consumindo gente que nunca esteve lá. Rascunho conta —
 * o RDO do dia costuma passar horas em rascunho antes de ser enviado, e um
 * rateio que só enxerga o enviado mostraria o mês sempre um passo atrasado.
 */
function rdoContaParaORateio(registro: LocalRdoRecord): boolean {
  if (registro.canceladoEm) return false;
  return registro.statusRdo !== "CANCELADA";
}

/**
 * A lista de mão de obra do RDO, quando o conteúdo já está aqui.
 *
 * <p>Devolve `null` — e não uma lista vazia — para o cabeçalho sem conteúdo.
 * A diferença entre "não sei" e "ninguém" é justamente o que esta função
 * existe para preservar.
 */
function maoDeObraDoPayload(
  payload: Record<string, unknown>,
): Record<string, unknown>[] | null {
  const bruto = payload.maoObra;
  if (!Array.isArray(bruto)) return null;
  return bruto.filter(
    (item): item is Record<string, unknown> =>
      typeof item === "object" && item !== null,
  );
}

/**
 * A pessoa entrou no dia?
 *
 * <p>No rascunho local, a lista guarda todo mundo que poderia ter vindo do RDO
 * anterior, e só quem está marcado trabalhou de fato. O que vem do servidor já
 * chega filtrado e não traz a marca — por isso a ausência dela significa
 * "sim", e não "não".
 */
function pessoaTrabalhouNoDia(item: Record<string, unknown>): boolean {
  return item.selected !== false;
}

/**
 * Quem responde pela frente daquele RDO.
 *
 * <p>Três portas, nesta ordem, e a razão de haver três é que o Córtex nunca
 * exigiu a primeira: o campo de encarregado existe no RDO desde sempre, mas
 * quem preenche o documento em campo não o digita — ele chega preenchido pela
 * importação de planilha e pela clonagem. Sem as outras duas portas, a coluna
 * "Encarregado" da tela nasceria vazia para todo RDO feito no aplicativo, que
 * é justamente o caso comum.
 *
 * <ol>
 *   <li>o campo declarado no RDO, quando alguém o escreveu;</li>
 *   <li>a pessoa da mão de obra cujo cargo diz que ela é encarregada — é assim
 *       que a frente se identifica no apontamento do dia;</li>
 *   <li>o apontador do RDO, que é quem assina o documento quando não há
 *       encarregado declarado.</li>
 * </ol>
 */
function encarregadoDoRdo(
  payload: Record<string, unknown>,
  maoDeObra: readonly Record<string, unknown>[],
): string {
  const declarado = texto(payload.encarregadoObra);
  if (declarado) return declarado;

  for (const item of maoDeObra) {
    const cargo = texto(item.cargo)
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .toUpperCase();
    if (cargo.includes("ENCARREGADO")) {
      const nome = texto(item.nomeColaborador);
      if (nome) return nome;
    }
  }

  return texto(payload.apontadorRdo);
}

/**
 * O que o rateio precisa saber de um RDO, venha ele de onde vier.
 *
 * <p>É um recorte do registro local de propósito: o mesmo tipo descreve o que
 * está no aparelho e o que o servidor devolve, e por isso a leitura do
 * encarregado — que tem três portas e é fácil de escrever diferente duas vezes
 * — acontece num lugar só.
 */
export interface RdoParaRateio {
  id: string;
  obraId: string;
  dataRdo: string;
  payload: Record<string, unknown>;
}

/** Transforma um RDO nos apontamentos que ele carrega. */
export function apontamentosDoRdo(
  registro: RdoParaRateio,
): ApontamentoDeMaoDeObra[] | null {
  const maoDeObra = maoDeObraDoPayload(registro.payload);
  if (maoDeObra === null) return null;

  const encarregado = encarregadoDoRdo(registro.payload, maoDeObra);
  const apontamentos: ApontamentoDeMaoDeObra[] = [];

  for (const item of maoDeObra) {
    if (!pessoaTrabalhouNoDia(item)) continue;
    const colaboradorId = texto(item.colaboradorId);
    const nome = texto(item.nomeColaborador);
    if (!colaboradorId && !nome) continue;
    apontamentos.push({
      colaboradorId: colaboradorId || null,
      nome,
      funcao: texto(item.cargo),
      obraId: registro.obraId,
      obraNome: texto(registro.payload.obraNome),
      data: registro.dataRdo,
      encarregado,
      rdoId: registro.id,
    });
  }

  return apontamentos;
}

/**
 * Lê do aparelho os apontamentos de um período.
 *
 * <p>Não vai à rede: é a leitura que sustenta o modo offline. Quem quiser o
 * retrato completo do servidor pede em outro lugar e cai aqui quando a rede
 * não responde.
 */
export async function lerApontamentosDoAparelho(
  filtro: FiltroDeApontamentos,
): Promise<LeituraDeApontamentos> {
  const registros = await listLocalRdos();
  return extrairApontamentos(registros, filtro);
}

/** A parte pura da leitura, separada para poder ser testada sem banco. */
export function extrairApontamentos(
  registros: readonly LocalRdoRecord[],
  filtro: FiltroDeApontamentos,
): LeituraDeApontamentos {
  const obrasEscolhidas =
    filtro.obraIds && filtro.obraIds.length > 0
      ? new Set(filtro.obraIds)
      : null;

  const apontamentos: ApontamentoDeMaoDeObra[] = [];
  let rdosLidos = 0;
  let rdosSemConteudo = 0;
  let rdosSemMaoDeObra = 0;

  for (const registro of registros) {
    if (!rdoContaParaORateio(registro)) continue;
    if (registro.dataRdo < filtro.inicio || registro.dataRdo > filtro.fim) {
      continue;
    }
    if (obrasEscolhidas && !obrasEscolhidas.has(registro.obraId)) continue;

    const doRdo = apontamentosDoRdo(registro);
    if (doRdo === null) {
      rdosSemConteudo += 1;
      continue;
    }
    rdosLidos += 1;
    if (doRdo.length === 0) rdosSemMaoDeObra += 1;
    apontamentos.push(...doRdo);
  }

  return { apontamentos, rdosLidos, rdosSemConteudo, rdosSemMaoDeObra };
}
