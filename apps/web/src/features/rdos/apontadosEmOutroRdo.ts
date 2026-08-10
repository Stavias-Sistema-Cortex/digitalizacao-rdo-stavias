/**
 * Quem já foi apontado em outro RDO do mesmo dia.
 *
 * <p>Numa obra com duas frentes, o mesmo operador e a mesma máquina aparecem em
 * dois RDOs do mesmo dia com naturalidade — e às vezes com razão: a
 * retroescavadeira atendeu as duas frentes, o encarregado passou nas duas. Mas
 * às vezes é engano, e o engano só aparece no fechamento do mês, quando a
 * mesma hora foi apontada duas vezes e ninguém lembra qual das duas era real.
 *
 * <p>Por isso avisa, e não impede. Barrar transformaria o caso legítimo em um
 * problema sem saída em campo, onde não há a quem perguntar. Um aviso ao lado
 * do nome devolve a decisão a quem estava lá.
 *
 * <p>A leitura é do aparelho, não do servidor: o RDO da outra frente pode ter
 * sido escrito hoje, sem rede, e ainda não ter subido. O que o servidor sabe
 * chega aqui pela reconciliação, junto com o resto.
 */

import { getCortexDb } from "../../lib/db/cortexDb";
import type { LocalRdoRecord } from "../../lib/db/db.types";

export interface ApontamentosDoDia {
  /** colaboradorId → número do RDO em que já aparece. */
  pessoas: ReadonlyMap<string, string>;
  /** assetId → número do RDO em que já aparece. */
  equipamentos: ReadonlyMap<string, string>;
}

const VAZIO: ApontamentosDoDia = {
  pessoas: new Map(),
  equipamentos: new Map(),
};

function lista(valor: unknown): Record<string, unknown>[] {
  return Array.isArray(valor)
    ? valor.filter(
        (item): item is Record<string, unknown> =>
          typeof item === "object" && item !== null && !Array.isArray(item),
      )
    : [];
}

function texto(valor: unknown): string {
  return typeof valor === "string" ? valor.trim() : "";
}

/**
 * Lê de um RDO quem ele aponta, para comparar com o que está sendo montado.
 *
 * <p>Exportada separada da leitura do banco porque a regra — o que conta como
 * "apontado" — é a parte que erra, e ela precisa ser conferível sem IndexedDB.
 */
export function apontamentosDoRdo(
  record: Pick<LocalRdoRecord, "numeroRdo" | "payload">,
): { pessoas: string[]; equipamentos: string[] } {
  const payload = record.payload ?? {};
  const pessoas = lista(payload.maoObra)
    // Desmarcado não é apontamento: a pessoa está na lista daquele RDO e não
    // trabalhou nele. Avisar sobre ela seria avisar sobre nada.
    .filter((item) => item.selected !== false)
    .map((item) => texto(item.colaboradorId))
    .filter(Boolean);
  const equipamentos = lista(payload.equipamentos)
    .map((item) => texto(item.assetId))
    .filter(Boolean);
  return { pessoas, equipamentos };
}

/**
 * Varre os RDOs do aparelho na mesma obra e na mesma data.
 *
 * <p>O RDO que está aberto é excluído por identidade: ele não pode avisar sobre
 * si mesmo. Apagado também sai — um RDO cancelado não aponta mais ninguém.
 */
export async function apontadosEmOutroRdo(
  obraId: string,
  dataRdo: string,
  rdoAtualId: string,
): Promise<ApontamentosDoDia> {
  if (!obraId.trim() || !dataRdo.trim()) {
    return VAZIO;
  }
  const database = await getCortexDb();
  const daObra = await database.getAllFromIndex("rdos", "by-obra-id", obraId);
  const pessoas = new Map<string, string>();
  const equipamentos = new Map<string, string>();

  for (const record of daObra) {
    if (record.id === rdoAtualId) continue;
    if (record.canceladoEm) continue;
    if (record.dataRdo !== dataRdo) continue;
    const onde = record.numeroRdo || "outro RDO";
    const { pessoas: quem, equipamentos: quais } = apontamentosDoRdo(record);
    for (const colaboradorId of quem) {
      if (!pessoas.has(colaboradorId)) pessoas.set(colaboradorId, onde);
    }
    for (const assetId of quais) {
      if (!equipamentos.has(assetId)) equipamentos.set(assetId, onde);
    }
  }

  return { pessoas, equipamentos };
}

/** A frase que vai ao lado do nome, ou nada quando não há o que dizer. */
export function avisoDeApontamentoRepetido(
  onde: string | undefined,
): string | null {
  return onde ? `Já apontado hoje no ${onde}.` : null;
}
