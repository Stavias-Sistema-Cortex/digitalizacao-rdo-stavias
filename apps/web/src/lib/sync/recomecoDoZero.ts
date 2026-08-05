import { deleteDB } from "idb";

import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import { currentDataDatabaseName } from "../db/localDataNamespace";

/**
 * Apagar a base local deste aparelho e começar de novo do servidor.
 *
 * <p>Existe para o caso em que a fila parou de andar por um motivo que já foi
 * corrigido, mas as linhas travadas de antes continuam ali. Descartar as mortas
 * uma a uma resolve o caso comum; quando não resolve, a saída honesta é
 * declarar bancarrota do que é local e reconstruir a partir do que o servidor
 * tem — que é a verdade de qualquer jeito.
 *
 * <p>É destrutivo e o preço é exato: some tudo que ainda não subiu. Por isso
 * {@link contarTrabalhoQueSeriaPerdido} vem antes, para que a tela diga o
 * número, e não "alguns registros". A pessoa precisa saber se está jogando fora
 * três apontamentos ou trinta.
 */

/** O que ainda não chegou ao servidor e sumiria de vez. */
export interface TrabalhoNaoEnviado {
  /** Mutações que ainda podem subir — PENDING, IN_REVIEW, tentando. */
  aindaPodemSubir: number;
  /** Recusadas ou em conflito: já não sobem, mas guardam o que foi digitado. */
  semSaida: number;
}

export async function contarTrabalhoQueSeriaPerdido(): Promise<
  TrabalhoNaoEnviado
> {
  const database = await getCortexDb();
  const todas = await database.getAll("outbox_mutations");
  let aindaPodemSubir = 0;
  let semSaida = 0;
  for (const mutation of todas) {
    if (mutation.status === "SYNCED") continue;
    if (mutation.status === "REJECTED" || mutation.status === "CONFLICT") {
      semSaida += 1;
      continue;
    }
    aindaPodemSubir += 1;
  }
  return { aindaPodemSubir, semSaida };
}

/**
 * Fecha as conexões e apaga a base deste escopo.
 *
 * <p>O nome é resolvido antes de fechar: depois de {@link closeCortexDb} o mapa
 * de conexões está vazio, mas o nome vem do escopo da sessão e não do mapa, de
 * modo que resolver primeiro apenas evita depender dessa ordem.
 *
 * <p>Fechar antes de apagar não é zelo: uma conexão aberta bloqueia o
 * `deleteDB`, que fica pendurado esperando — e a tela ficaria travada num botão
 * que não volta.
 */
export async function apagarBaseLocal(): Promise<void> {
  const databaseName = await currentDataDatabaseName();
  await closeCortexDb();
  await deleteDB(databaseName);
}
