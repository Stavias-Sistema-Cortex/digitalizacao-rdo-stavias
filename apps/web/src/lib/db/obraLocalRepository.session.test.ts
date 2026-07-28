// @vitest-environment jsdom

import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
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
import type { ObraLocalRecord } from "./db.types";
import { databaseNameForScope } from "./localDataNamespace";
import { mergeObraLocal } from "./obraLocalRepository";

const ALFA_ID = "00000000-0000-4000-8000-000000000011";
const BETA_ID = "00000000-0000-4000-8000-000000000012";
const OBRA_ID = "00000000-0000-4000-8000-000000000013";

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

function obra(): ObraLocalRecord {
  return {
    id: OBRA_ID,
    codigoContrato: "CTR-SESSION",
    nome: "Obra da sessão anterior",
    cliente: null,
    cidade: null,
    uf: null,
    rodovia: null,
    status: "ATIVA",
    observacoes: null,
    latitude: null,
    longitude: null,
    valorContratual: null,
    arquivadoEm: null,
    syncStatus: "SYNCED",
    updatedAt: "2026-07-28T13:00:00.000Z",
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
  vi.unstubAllGlobals();
});

describe("worksite merge session transaction", () => {
  it("aborts the write when AUTH_SESSION_CHANGED_EVENT fires during put", async () => {
    await getCortexDb();
    const guard = captureOnlineSyncSession();
    const originalStructuredClone =
      globalThis.structuredClone.bind(globalThis);
    let switchedDuringPut = false;
    vi.stubGlobal(
      "structuredClone",
      (value: unknown, options?: StructuredSerializeOptions) => {
        if (
          !switchedDuringPut &&
          typeof value === "object" &&
          value !== null &&
          "id" in value &&
          value.id === OBRA_ID
        ) {
          switchedDuringPut = true;
          setSession(betaSession());
        }
        return originalStructuredClone(value, options);
      },
    );

    await expect(
      mergeObraLocal(obra(), guard),
    ).rejects.toThrow(
      "A sessão mudou durante a sincronização.",
    );
    expect(switchedDuringPut).toBe(true);

    setSession(alfaSession());
    const alfaDatabase = await getCortexDb();
    expect(
      await alfaDatabase.get("obras", OBRA_ID),
    ).toBeUndefined();
  });
});
