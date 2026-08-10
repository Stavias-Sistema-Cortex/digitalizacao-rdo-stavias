import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "./cortexDb";
import { databaseNameForScope } from "./localDataNamespace";
import { createEmptyRdo } from "../../features/rdos/createEmptyRdo";
import { saveLocalPendingRdoDraftAtomically } from "./localRdoService";
import type { RdoDraft } from "../../features/rdos/rdo.types";

/**
 * A pista Sul começa no km 400 e termina no 398.
 *
 * <p>O salvamento recusava isso com "O KM final do trecho interditado não pode
 * ser menor que o KM inicial." — uma frase que descreve uma rodovia que só
 * existe em um sentido. Interdita-se do 400 ao 398, e é assim que se escreve na
 * ordem de serviço.
 *
 * <p>O sintoma era exatamente o que o campo relatou: a conta de extensão já
 * tinha sido corrigida para medir em valor absoluto, então a tela mostrava os
 * dois quilômetros de trecho e o botão de salvar recusava, mandando inverter o
 * que estava certo.
 */

const OBRA_ID = "00000000-0000-4000-8000-000000000801";
const RDO_ID = "00000000-0000-4000-8000-000000000802";

let databaseName = "";

function rascunho(patch: Partial<RdoDraft>): RdoDraft {
  return {
    ...createEmptyRdo(),
    id: RDO_ID,
    obraId: OBRA_ID,
    dataRdo: "2026-08-10",
    numeroRdo: "RDO-0042",
    ...patch,
  };
}

async function guardado(): Promise<Record<string, unknown>> {
  const database = await getCortexDb();
  const fila = await database.getAllFromIndex(
    "outbox_mutations",
    "by-entity-id",
    RDO_ID,
  );
  return fila.at(-1)?.payload as Record<string, unknown>;
}

beforeEach(async () => {
  const userId = crypto.randomUUID();
  setSession({
    colaboradorId: userId,
    nome: "Encarregado",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

describe("trecho de quilometragem decrescente", () => {
  it("salva o trecho interditado que começa no km maior", async () => {
    await saveLocalPendingRdoDraftAtomically(
      rascunho({
        kmInicialInterditado: "400",
        kmFinalInterditado: "398",
      }),
    );

    expect(await guardado()).toMatchObject({
      kmInicialInterditado: "400",
      kmFinalInterditado: "398",
    });
  });

  it("salva o trecho programado que começa no km maior", async () => {
    await saveLocalPendingRdoDraftAtomically(
      rascunho({
        kmInicialProgramado: "206,822",
        kmFinalProgramado: "206,685",
      }),
    );

    expect(await guardado()).toMatchObject({
      kmInicialProgramado: "206,822",
      kmFinalProgramado: "206,685",
    });
  });

  /*
   * O km escrito com ponto é a notação que a base inteira guarda. Lido com a
   * regra de milhar dos demais campos, `206.822` viraria duzentos e seis mil —
   * e a validação de ordem, quando existia, comparava justamente esse número
   * inventado contra o outro.
   */
  it("salva o trecho escrito com ponto decimal", async () => {
    await saveLocalPendingRdoDraftAtomically(
      rascunho({
        kmInicialInterditado: "206.822",
        kmFinalInterditado: "206.685",
      }),
    );

    expect(await guardado()).toMatchObject({
      kmInicialInterditado: "206.822",
      kmFinalInterditado: "206.685",
    });
  });
});
