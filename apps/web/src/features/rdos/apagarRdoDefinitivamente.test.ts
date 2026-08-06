import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../auth/authSession";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";

const api = vi.hoisted(() => ({ apiFetch: vi.fn() }));
vi.mock("../../lib/api/apiClient", () => api);

import { apagarRdoDefinitivamente } from "./rdoLifecycle";

const COLABORADOR_ID = "00000000-0000-4000-8000-00000000cd01";
const OBRA_ID = "00000000-0000-4000-8000-00000000cd02";
const RDO_ID = "00000000-0000-4000-8000-00000000cd03";

let databaseName = "";

function rdoNoServidor() {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    numeroRdo: "RDO-0007",
    dataRdo: "2026-08-05",
    statusRdo: "RASCUNHO",
    syncStatus: "SYNCED",
    versaoEntidade: 3,
    canceladoEm: "2026-08-05T12:00:00.000Z",
    payload: {},
    atualizadoEm: "2026-08-05T12:00:00.000Z",
  };
}

function mutacaoDoRdo(clientMutationId: string) {
  return {
    clientMutationId,
    entidadeTipo: "RDO",
    entidadeId: RDO_ID,
    operacao: "ATUALIZAR_RDO",
    status: "PENDING",
    baseVersao: 3,
    payload: {},
    tentativas: 0,
    createdAt: "2026-08-05T12:00:00.000Z",
    updatedAt: "2026-08-05T12:00:00.000Z",
  };
}

describe("apagar RDO definitivamente", () => {
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
    await database.clear("rdos");
    await database.clear("outbox_mutations");
    await database.put("rdos", rdoNoServidor() as never);
    await database.put("outbox_mutations", mutacaoDoRdo("m-1") as never);
    api.apiFetch.mockReset();
  });

  afterEach(async () => {
    await closeCortexDb();
    if (databaseName) await deleteDB(databaseName);
    clearSession();
  });

  /*
   * O ponto do gesto: a fila do RDO vai junto. Apagar só no servidor — que é
   * o que o console do banco faz — deixaria estas mutações apontando para
   * linhas que já não existem, e cada uma viraria um conflito novo.
   */
  it("apaga no servidor e leva a fila local junto", async () => {
    api.apiFetch.mockResolvedValue({ ok: true });

    await apagarRdoDefinitivamente(rdoNoServidor() as never);

    expect(api.apiFetch).toHaveBeenCalledWith(
      `/rdos/${RDO_ID}`,
      { method: "DELETE" },
    );
    const database = await getCortexDb();
    expect(await database.get("rdos", RDO_ID)).toBeUndefined();
    expect(await database.getAll("outbox_mutations")).toEqual([]);
  });

  /*
   * A ordem é a única que não deixa lixo. Se o local sumisse primeiro, uma
   * recusa do servidor deixaria o RDO apagado aqui e vivo lá — e a próxima
   * hidratação o traria de volta sem explicação nenhuma.
   */
  it("não toca no local quando o servidor recusa", async () => {
    api.apiFetch.mockResolvedValue({
      ok: false,
      text: async () =>
        JSON.stringify({ message: "Este RDO é a base de RDO-0008." }),
    });

    await expect(
      apagarRdoDefinitivamente(rdoNoServidor() as never),
    ).rejects.toThrow("Este RDO é a base de RDO-0008.");

    const database = await getCortexDb();
    expect(await database.get("rdos", RDO_ID)).toBeDefined();
    expect(await database.getAll("outbox_mutations")).toHaveLength(1);
  });

  /*
   * A mensagem do servidor é a que explica o motivo — base de outro RDO,
   * serviço já medido. Trocá-la por uma genérica apagaria a razão e mandaria
   * a pessoa tentar de novo sem saber o que mudar.
   */
  it("preserva o motivo que o servidor deu", async () => {
    api.apiFetch.mockResolvedValue({
      ok: false,
      text: async () =>
        JSON.stringify({
          message: "Este RDO tem 2 serviços já medidos e não pode ser apagado.",
        }),
    });

    await expect(
      apagarRdoDefinitivamente(rdoNoServidor() as never),
    ).rejects.toThrow("2 serviços já medidos");
  });

  /*
   * RDO que o servidor nunca aceitou não tem o que apagar lá. Chamar assim
   * mesmo devolveria 404 e travaria a limpeza de algo que só existe aqui.
   */
  it("não chama o servidor para RDO que ele nunca conheceu", async () => {
    const database = await getCortexDb();
    const soLocal = {
      ...rdoNoServidor(),
      syncStatus: "PENDING_SYNC",
      versaoEntidade: null,
    };
    await database.put("rdos", soLocal as never);

    await apagarRdoDefinitivamente(soLocal as never);

    expect(api.apiFetch).not.toHaveBeenCalled();
    expect(await (await getCortexDb()).get("rdos", RDO_ID)).toBeUndefined();
  });
});
