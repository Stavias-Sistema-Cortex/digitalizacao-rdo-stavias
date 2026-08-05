import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import { databaseNameForScope } from "../db/localDataNamespace";
import {
  apagarBaseLocal,
  contarTrabalhoQueSeriaPerdido,
} from "./recomecoDoZero";

const COLABORADOR_ID = "00000000-0000-4000-8000-00000000ab01";
const OBRA_ID = "00000000-0000-4000-8000-00000000ab02";

let databaseName = "";

function mutacao(clientMutationId: string, status: string) {
  return {
    clientMutationId,
    entidadeTipo: "EQUIPE",
    entidadeId: OBRA_ID,
    operacao: "ATUALIZAR_EQUIPE",
    status,
    baseVersao: 1,
    payload: {},
    tentativas: 0,
    createdAt: "2026-08-05T00:00:00.000Z",
    updatedAt: "2026-08-05T00:00:00.000Z",
  };
}

describe("recomeçar do zero neste aparelho", () => {
  beforeEach(async () => {
    setSession({
      colaboradorId: COLABORADOR_ID,
      nome: "Beta",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: [OBRA_ID],
      expiraEm: "2099-01-01T00:00:00.000Z",
    });
    databaseName = await databaseNameForScope(
      COLABORADOR_ID,
      `BETA:${OBRA_ID}`,
    );
    const database = await getCortexDb();
    await database.clear("outbox_mutations");
  });

  afterEach(async () => {
    await closeCortexDb();
    if (databaseName) await deleteDB(databaseName);
    clearSession();
  });

  /*
   * O número é o ponto do aviso. "Alguns registros" faz a pessoa decidir às
   * cegas se está jogando fora três apontamentos ou trinta.
   */
  it("separa o que ainda pode subir do que já não tem saída", async () => {
    const database = await getCortexDb();
    for (const registro of [
      mutacao("pendente-1", "PENDING"),
      mutacao("pendente-2", "PENDING"),
      mutacao("em-revisao", "IN_REVIEW"),
      mutacao("recusada", "REJECTED"),
      mutacao("conflito", "CONFLICT"),
      mutacao("ja-subiu", "SYNCED"),
    ]) {
      await database.put("outbox_mutations", registro as never);
    }

    expect(await contarTrabalhoQueSeriaPerdido()).toEqual({
      aindaPodemSubir: 3,
      semSaida: 2,
    });
  });

  /* O que já está no servidor não conta como perda. */
  it("fila só com o que já subiu não anuncia prejuízo", async () => {
    const database = await getCortexDb();
    await database.put("outbox_mutations", mutacao("ja-subiu", "SYNCED") as never);

    expect(await contarTrabalhoQueSeriaPerdido()).toEqual({
      aindaPodemSubir: 0,
      semSaida: 0,
    });
  });

  /*
   * Apagar de verdade, e sem ficar pendurado: uma conexão aberta bloqueia o
   * deleteDB, que espera calado — a tela travaria num botão que não volta.
   */
  it("apaga a base e a seguinte nasce vazia", async () => {
    const database = await getCortexDb();
    await database.put("outbox_mutations", mutacao("pendente-1", "PENDING") as never);
    expect((await contarTrabalhoQueSeriaPerdido()).aindaPodemSubir).toBe(1);

    await apagarBaseLocal();

    const nova = await getCortexDb();
    expect(await nova.getAll("outbox_mutations")).toEqual([]);
  });
});
