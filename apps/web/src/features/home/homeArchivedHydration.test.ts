import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../auth/authSession";
import { closeCortexDb } from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import {
  getObraLocal,
  listObrasLocais,
} from "../../lib/db/obraLocalRepository";

const mocks = vi.hoisted(() => ({
  buscarObrasArquivadas: vi.fn(),
}));

vi.mock("./homeApi", async (importActual) => ({
  ...await importActual<typeof import("./homeApi")>(),
  buscarObrasArquivadas: mocks.buscarObrasArquivadas,
}));

import { hydrateObrasArquivadas } from "./homeHydration";

const USER_ID = "00000000-0000-4000-8000-000000000001";
const WORKSITE_ID = "00000000-0000-4000-8000-000000000002";
let databaseName = "";

beforeEach(async () => {
  setSession({
    colaboradorId: USER_ID,
    nome: "Administradora",
    papelAcesso: "ALFA",
    escopoGlobal: true,
    obraIds: [],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(USER_ID, "ALFA:GLOBAL");
  mocks.buscarObrasArquivadas.mockResolvedValue([{
    id: WORKSITE_ID,
    codigoContrato: "CTR-ARQ",
    codigoCw: "CW-ARQ",
    codigoInterno: "INT-ARQ",
    nome: "Contorno arquivado",
    cliente: "DNIT",
    descricao: "Trecho concluído",
    cidade: "Campo Grande",
    uf: "MS",
    rodovia: "BR-262",
    latitude: "-20.45",
    longitude: "-54.61",
    status: "INATIVA",
    fonteCriacao: "IMPORTACAO",
    fonteArquivo: "obras.xlsx",
    observacoes: "Preservar histórico",
    criadoEm: "2026-07-01T10:00:00.000Z",
    atualizadoEm: "2026-07-28T13:00:00.000Z",
    arquivadoEm: "2026-07-28T14:00:00.000Z",
    versaoLinha: 7,
  }]);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
  vi.clearAllMocks();
});

describe("Alfa archived worksite hydration", () => {
  it("maps and persists the remote trash on a clean device for later offline use", async () => {
    expect(await listObrasLocais({ includeArchived: true })).toEqual([]);

    await expect(hydrateObrasArquivadas()).resolves.toBe(1);
    await closeCortexDb();

    expect(await getObraLocal(WORKSITE_ID)).toMatchObject({
      id: WORKSITE_ID,
      codigoContrato: "CTR-ARQ",
      codigoInterno: "INT-ARQ",
      nome: "Contorno arquivado",
      cliente: "DNIT",
      descricao: "Trecho concluído",
      cidade: "Campo Grande",
      uf: "MS",
      rodovia: "BR-262",
      latitude: -20.45,
      longitude: -54.61,
      status: "INATIVA",
      fonteArquivo: "obras.xlsx",
      observacoes: "Preservar histórico",
      arquivadoEm: "2026-07-28T14:00:00.000Z",
      versaoEntidade: 7,
      syncStatus: "SYNCED",
    });
    expect(await listObrasLocais()).toEqual([]);
    expect(await listObrasLocais({ includeArchived: true })).toHaveLength(1);
  });
});
