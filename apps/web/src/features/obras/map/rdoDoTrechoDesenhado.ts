import { listLocalRdosByObra } from "../../../lib/db/rdoRepository";
import type { LocalRdoRecord } from "../../../lib/db/db.types";

/**
 * Qual RDO um trecho desenhado alimenta.
 *
 * <p>Desenhar no mapa e apontar no RDO eram dois registros paralelos, e o
 * paralelismo era o defeito: a geometria nascia com `objetoTipo: "TRECHO"` —
 * uma auto-referência, sem entidade do outro lado — então o desenho não sabia
 * de qual dia nem de qual serviço ele falava. Apagar o apontamento deixava o
 * desenho na tela, e a rodovia digitada à mão podia divergir da obra sem que
 * nada reconciliasse.
 *
 * <p>Agora são duas portas para o mesmo registro. O desenho pertence ao RDO do
 * dia daquela obra, e é por isso que ele some quando o apontamento some.
 */

/** O RDO daquele dia, quando já existe neste aparelho. */
export function acharRdoDoDia(
  rdos: readonly LocalRdoRecord[],
  data: string,
): LocalRdoRecord | null {
  const doDia = rdos.filter(
    (rdo) =>
      rdo.dataRdo === data
      && rdo.statusRdo !== "CANCELADA"
      && !rdo.canceladoEm,
  );
  if (doDia.length === 0) {
    return null;
  }
  // Mais de um RDO no mesmo dia é possível — turnos, frentes distintas. O
  // desenho vai para o mais recentemente mexido, que é onde a pessoa está
  // trabalhando agora; escolher pelo mais antigo mandaria o trecho de hoje
  // para um apontamento que ela já fechou.
  return doDia.reduce((maisRecente, candidato) =>
    candidato.updatedAt > maisRecente.updatedAt ? candidato : maisRecente,
  );
}

export interface RdoDoTrecho {
  rdoId: string;
  /** Verdadeiro quando o desenho vai criar o apontamento do dia. */
  criaRdo: boolean;
}

/**
 * Resolve — sem criar nada ainda — a que RDO o desenho pertence.
 *
 * <p>Devolver a decisão antes de executá-la é o que permite avisar quem
 * desenha que um apontamento novo vai nascer junto. Criar em silêncio um RDO
 * que a pessoa não pediu seria surpresa, e RDO é documento de obra.
 */
export async function resolverRdoDoTrecho(input: {
  obraId: string;
  data: string;
}): Promise<RdoDoTrecho> {
  const existente = acharRdoDoDia(
    await listLocalRdosByObra(input.obraId),
    input.data,
  );
  if (existente) {
    return { rdoId: existente.id, criaRdo: false };
  }
  return { rdoId: crypto.randomUUID(), criaRdo: true };
}
