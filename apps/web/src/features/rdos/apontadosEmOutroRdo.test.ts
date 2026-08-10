import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { clearSession, setSession } from "../auth/authSession";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import type { LocalRdoRecord } from "../../lib/db/db.types";
import {
  apontadosEmOutroRdo,
  apontamentosDoRdo,
  avisoDeApontamentoRepetido,
} from "./apontadosEmOutroRdo";

/**
 * A mesma pessoa e a mesma máquina em duas frentes do mesmo dia.
 *
 * <p>Às vezes é verdade — a retroescavadeira atendeu as duas frentes, o
 * encarregado passou nas duas — e às vezes é engano, que só aparece no
 * fechamento do mês, quando a mesma hora foi apontada duas vezes e ninguém
 * lembra qual era real. Por isso avisa e não impede.
 */

const OBRA = "00000000-0000-4000-8000-000000000901";
const OUTRA_OBRA = "00000000-0000-4000-8000-000000000902";
const HOJE = "2026-08-10";

let databaseName = "";

function rdo(patch: Partial<LocalRdoRecord>): LocalRdoRecord {
  return {
    id: crypto.randomUUID(),
    obraId: OBRA,
    programacaoId: null,
    numeroRdo: "RDO-0001",
    dataRdo: HOJE,
    statusRdo: "RASCUNHO",
    syncStatus: "LOCAL_ONLY",
    versaoEntidade: null,
    payload: {},
    createdAt: HOJE,
    updatedAt: HOJE,
    ...patch,
  };
}

async function gravar(...registros: LocalRdoRecord[]): Promise<void> {
  const database = await getCortexDb();
  for (const registro of registros) {
    await database.put("rdos", registro);
  }
}

beforeEach(async () => {
  const userId = crypto.randomUUID();
  setSession({
    colaboradorId: userId,
    nome: "Encarregado",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA, OUTRA_OBRA],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA}`);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

describe("leitura de um RDO", () => {
  it("conta quem está marcado e a frota lançada", () => {
    expect(
      apontamentosDoRdo({
        numeroRdo: "RDO-0002",
        payload: {
          maoObra: [
            { colaboradorId: "worker-a", selected: true },
            { colaboradorId: "worker-b", selected: true },
          ],
          equipamentos: [{ assetId: "asset-esc" }],
        },
      }),
    ).toEqual({
      pessoas: ["worker-a", "worker-b"],
      equipamentos: ["asset-esc"],
    });
  });

  /*
   * Desmarcado não é apontamento: a pessoa está na lista daquele RDO e não
   * trabalhou nele. Avisar sobre ela seria avisar sobre nada.
   */
  it("ignora quem está na lista do outro RDO sem estar marcado", () => {
    expect(
      apontamentosDoRdo({
        numeroRdo: "RDO-0002",
        payload: {
          maoObra: [{ colaboradorId: "worker-a", selected: false }],
        },
      }).pessoas,
    ).toEqual([]);
  });

  /*
   * A máquina de terceiro não tem cadastro, então não tem como ser reconhecida
   * como a mesma em dois RDOs. Não avisar é a leitura honesta.
   */
  it("ignora a linha sem identidade de cadastro", () => {
    expect(
      apontamentosDoRdo({
        numeroRdo: "RDO-0002",
        payload: {
          maoObra: [{ nomeColaborador: "Ajudante", selected: true }],
          equipamentos: [{ descricao: "Betoneira do empreiteiro" }],
        },
      }),
    ).toEqual({ pessoas: [], equipamentos: [] });
  });

  it("aguenta um payload sem as listas", () => {
    expect(apontamentosDoRdo({ numeroRdo: "RDO-0002", payload: {} })).toEqual({
      pessoas: [],
      equipamentos: [],
    });
  });
});

describe("varredura dos RDOs do dia", () => {
  it("encontra quem já foi apontado, dizendo em qual RDO", async () => {
    await gravar(
      rdo({
        id: "outro",
        numeroRdo: "RDO-0042",
        payload: {
          maoObra: [{ colaboradorId: "worker-a", selected: true }],
          equipamentos: [{ assetId: "asset-esc" }],
        },
      }),
    );

    const encontrados = await apontadosEmOutroRdo(OBRA, HOJE, "atual");

    expect(encontrados.pessoas.get("worker-a")).toBe("RDO-0042");
    expect(encontrados.equipamentos.get("asset-esc")).toBe("RDO-0042");
    expect(avisoDeApontamentoRepetido(encontrados.pessoas.get("worker-a")))
      .toBe("Já apontado hoje no RDO-0042.");
  });

  it("não avisa sobre o próprio RDO que está aberto", async () => {
    await gravar(
      rdo({
        id: "atual",
        payload: { maoObra: [{ colaboradorId: "worker-a", selected: true }] },
      }),
    );

    expect((await apontadosEmOutroRdo(OBRA, HOJE, "atual")).pessoas.size)
      .toBe(0);
  });

  it("olha só a mesma data e a mesma obra", async () => {
    await gravar(
      rdo({
        id: "ontem",
        dataRdo: "2026-08-09",
        payload: { maoObra: [{ colaboradorId: "worker-a", selected: true }] },
      }),
      rdo({
        id: "outra-obra",
        obraId: OUTRA_OBRA,
        payload: { maoObra: [{ colaboradorId: "worker-b", selected: true }] },
      }),
    );

    expect((await apontadosEmOutroRdo(OBRA, HOJE, "atual")).pessoas.size)
      .toBe(0);
  });

  /** Um RDO cancelado não aponta mais ninguém. */
  it("ignora o RDO apagado", async () => {
    await gravar(
      rdo({
        id: "apagado",
        canceladoEm: "2026-08-10T12:00:00Z",
        payload: { maoObra: [{ colaboradorId: "worker-a", selected: true }] },
      }),
    );

    expect((await apontadosEmOutroRdo(OBRA, HOJE, "atual")).pessoas.size)
      .toBe(0);
  });

  it("não consulta nada quando o RDO ainda não tem obra ou data", async () => {
    expect((await apontadosEmOutroRdo("", HOJE, "atual")).pessoas.size).toBe(0);
    expect((await apontadosEmOutroRdo(OBRA, "", "atual")).pessoas.size).toBe(0);
    expect(avisoDeApontamentoRepetido(undefined)).toBeNull();
  });
});
