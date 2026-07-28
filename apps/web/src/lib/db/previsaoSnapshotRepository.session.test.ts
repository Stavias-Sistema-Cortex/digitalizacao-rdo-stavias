// @vitest-environment jsdom

import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
} from "vitest";

import {
  clearSession,
  setSession,
  type AuthProfile,
} from "../../features/auth/authSession";
import {
  captureOnlineSyncSession,
} from "../sync/syncSession";
import {
  closeCortexDb,
  getCortexDb,
} from "./cortexDb";
import type { PrevisaoSnapshotRecord } from "./db.types";
import { databaseNameForScope } from "./localDataNamespace";
import { putPrevisaoSnapshot } from "./previsaoSnapshotRepository";

const ALFA_ID = "00000000-0000-4000-8000-000000000001";
const BETA_ID = "00000000-0000-4000-8000-000000000002";
const OBRA_ID = "00000000-0000-4000-8000-000000000003";
const SNAPSHOT_ID = "00000000-0000-4000-8000-000000000004";

let alfaDatabaseName = "";
let betaDatabaseName = "";

function alfaSession(): AuthProfile {
  return {
    colaboradorId: ALFA_ID,
    nome: "Administradora Alfa",
    papelAcesso: "ALFA",
    escopoGlobal: true,
    obraIds: [],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  };
}

function betaSession(): AuthProfile {
  return {
    colaboradorId: BETA_ID,
    nome: "Operadora Beta",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  };
}

beforeEach(async () => {
  const alfa = alfaSession();
  setSession(alfa);
  alfaDatabaseName = await databaseNameForScope(
    alfa.colaboradorId,
    "ALFA:GLOBAL",
  );
  const beta = betaSession();
  betaDatabaseName = await databaseNameForScope(
    beta.colaboradorId,
    `BETA:${OBRA_ID}`,
  );
});

afterEach(async () => {
  await closeCortexDb();
  if (alfaDatabaseName) await deleteDB(alfaDatabaseName);
  if (betaDatabaseName) await deleteDB(betaDatabaseName);
  clearSession();
});

describe("forecast snapshot session transaction", () => {
  it("aborts the write when AUTH_SESSION_CHANGED_EVENT fires during put", async () => {
    await getCortexDb();
    const guard = captureOnlineSyncSession();
    const record: PrevisaoSnapshotRecord = {
      id: SNAPSHOT_ID,
      obraId: OBRA_ID,
      dataReferencia: "2026-07-28",
      statusExecucao: "CALCULADO",
      producaoPlanejada: 10,
      producaoRealizada: 8,
      custoRealizado: null,
      custoPrevistoFinal: null,
      receitaPrevistaFinal: 1200,
      updatedAt: "2026-07-28T13:00:00.000Z",
    };
    Object.defineProperty(record, "updatedAt", {
      configurable: true,
      enumerable: true,
      get() {
        setSession(betaSession());
        return "2026-07-28T13:00:00.000Z";
      },
    });

    await expect(
      putPrevisaoSnapshot(record, guard),
    ).rejects.toThrow(
      "A sessão mudou durante a sincronização.",
    );

    setSession(alfaSession());
    const alfaDatabase = await getCortexDb();
    expect(
      await alfaDatabase.get(
        "previsao_snapshots",
        SNAPSHOT_ID,
      ),
    ).toBeUndefined();
  });
});
