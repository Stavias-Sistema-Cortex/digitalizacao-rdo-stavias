import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../auth/authSession";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";

const api = vi.hoisted(() => ({ apiFetch: vi.fn() }));
vi.mock("../../lib/api/apiClient", () => api);

import { apagarEquipeDefinitivamente } from "./apagarEquipe";

const COLABORADOR_ID = "00000000-0000-4000-8000-00000000ee01";
const OBRA_ID = "00000000-0000-4000-8000-00000000ee02";
const EQUIPE_ID = "00000000-0000-4000-8000-00000000ee03";

let databaseName = "";

function equipe() {
  return {
    id: EQUIPE_ID,
    obraPrincipalId: OBRA_ID,
    obraNome: "Quarta intervencao",
    nome: "FR Carlos",
    descricao: null,
    status: "ATIVA",
    inicioValidadeEm: "2026-07-05T00:00:00",
    fimValidadeEm: null,
    versaoEntidade: 18,
    criadoEm: "2026-07-05T00:00:00",
    atualizadoEm: "2026-08-05T00:00:00",
    membros: [],
  };
}

function mutacaoDaEquipe(clientMutationId: string, tentativas = 0) {
  return {
    clientMutationId,
    entidadeTipo: "EQUIPE",
    entidadeId: EQUIPE_ID,
    operacao: "ADICIONAR_MEMBRO_EQUIPE",
    status: "PENDING",
    baseVersao: 18,
    payload: {},
    tentativas,
    createdAt: "2026-08-05T12:00:00.000Z",
    updatedAt: "2026-08-05T12:00:00.000Z",
  };
}

describe("apagar equipe definitivamente", () => {
  beforeEach(async () => {
    setSession({
      colaboradorId: COLABORADOR_ID,
      nome: "Alfa",
      papelAcesso: "ALFA",
      escopoGlobal: true,
      obraIds: [],
      expiraEm: "2099-01-01T00:00:00.000Z",
    });
    databaseName = await databaseNameForScope(COLABORADOR_ID, "ALFA:GLOBAL");
    const database = await getCortexDb();
    await database.clear("teams");
    await database.clear("outbox_mutations");
    await database.put("teams", equipe() as never);
    await database.put(
      "outbox_mutations",
      mutacaoDaEquipe("presa-1", 164) as never,
    );
    await database.put("outbox_mutations", mutacaoDaEquipe("presa-2") as never);
    api.apiFetch.mockReset();
  });

  afterEach(async () => {
    await closeCortexDb();
    if (databaseName) await deleteDB(databaseName);
    clearSession();
  });

  /*
   * O ponto do gesto: a fila da equipe vai junto. Era isso que arquivar não
   * fazia — arquivar é só mais uma mutação na mesma fila doente.
   */
  it("apaga no servidor e leva a fila da equipe junto", async () => {
    api.apiFetch.mockResolvedValue({ ok: true, status: 200 });

    await apagarEquipeDefinitivamente(equipe() as never);

    expect(api.apiFetch).toHaveBeenCalledWith(
      `/equipes/${EQUIPE_ID}`,
      { method: "DELETE" },
    );
    const database = await getCortexDb();
    expect(await database.get("teams", EQUIPE_ID)).toBeUndefined();
    expect(await database.getAll("outbox_mutations")).toEqual([]);
  });

  /*
   * 404 não é recusa: é a equipe que o servidor nunca conheceu — criada
   * offline, com a criação presa na fila. O gesto vira só local, que é
   * exatamente o que se quer para sumir com o rascunho e a fila dele.
   */
  it("segue com a limpeza local quando o servidor nunca conheceu a equipe", async () => {
    api.apiFetch.mockResolvedValue({
      ok: false,
      status: 404,
      text: async () => "",
    });

    await apagarEquipeDefinitivamente(equipe() as never);

    const database = await getCortexDb();
    expect(await database.get("teams", EQUIPE_ID)).toBeUndefined();
    expect(await database.getAll("outbox_mutations")).toEqual([]);
  });

  /* Recusa de verdade não toca no local, e o motivo do servidor sobrevive. */
  it("não toca no local quando o servidor recusa", async () => {
    api.apiFetch.mockResolvedValue({
      ok: false,
      status: 403,
      text: async () =>
        JSON.stringify({ message: "Apenas o Alfa pode apagar equipes." }),
    });

    await expect(
      apagarEquipeDefinitivamente(equipe() as never),
    ).rejects.toThrow("Apenas o Alfa pode apagar equipes.");

    const database = await getCortexDb();
    expect(await database.get("teams", EQUIPE_ID)).toBeDefined();
    expect(await database.getAll("outbox_mutations")).toHaveLength(2);
  });

  /* A fila de OUTRA equipe não é tocada. */
  it("não leva a fila das outras equipes", async () => {
    const outra = {
      ...mutacaoDaEquipe("de-outra"),
      entidadeId: "00000000-0000-4000-8000-00000000ee99",
    };
    await (await getCortexDb()).put("outbox_mutations", outra as never);
    api.apiFetch.mockResolvedValue({ ok: true, status: 200 });

    await apagarEquipeDefinitivamente(equipe() as never);

    const restantes = await (await getCortexDb()).getAll("outbox_mutations");
    expect(restantes.map((m) => m.clientMutationId)).toEqual(["de-outra"]);
  });
});
