import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  fetch: vi.fn(),
  autoritativo: vi.fn(),
  obras: vi.fn(),
}));

vi.mock("../../lib/api/apiClient", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/api/apiClient")>()),
  apiFetch: api.fetch,
}));
vi.mock("./rdoLookupApi", () => ({
  buscarRdoAutoritativoPorId: api.autoritativo,
}));
vi.mock("./rdoCreationContextRepository", () => ({
  listCachedAuthorizedRdoWorksites: api.obras,
}));

import { clearSession, setSession } from "../auth/authSession";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import type { LocalRdoRecord } from "../../lib/db/db.types";
import { reconciliarRdosDoServidor } from "./rdosDoServidor";

/**
 * O RDO que existe no servidor precisa aparecer no aparelho de quem tem acesso.
 *
 * <p>A lista lia só o IndexedDB local, e o banco local é por pessoa — o nome
 * dele deriva de {@code {ownerId, escopo}}. Então cada aparelho enxergava
 * apenas o que ele próprio havia criado. Um encarregado abria a conta dele,
 * com acesso à obra e com os RDOs já sincronizados, e via a tela vazia; não
 * havia como descobrir o porquê, porque nada estava errado — nada trazia os
 * RDOs de volta.
 *
 * <p>Os dois testes que mais importam aqui não são os de descoberta: são os que
 * provam que a reconciliação não passa por cima do trabalho de campo.
 */

const OBRA_ID = "00000000-0000-4000-8000-000000000901";
const RDO_REMOTO = "00000000-0000-4000-8000-000000000902";

let databaseName = "";
let userId = "";

function resumo(overrides: Record<string, unknown> = {}) {
  return {
    id: RDO_REMOTO,
    obraId: OBRA_ID,
    numeroRdo: "RDO-0017",
    dataRdo: "2026-08-09",
    status: "ENVIADO",
    atualizadoEm: "2026-08-09T18:00:00.000Z",
    ...overrides,
  };
}

function respondeComLista(itens: unknown[]) {
  api.fetch.mockResolvedValue({
    ok: true,
    status: 200,
    headers: { get: () => "application/json" },
    json: async () => itens,
    text: async () => JSON.stringify(itens),
  } as unknown as Response);
}

function rdoLocal(overrides: Partial<LocalRdoRecord> = {}): LocalRdoRecord {
  return {
    id: RDO_REMOTO,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-0017",
    dataRdo: "2026-08-09",
    statusRdo: "RASCUNHO",
    syncStatus: "SYNCED",
    versaoEntidade: 3,
    payload: { observacoes: "vindo do servidor" },
    createdAt: "2026-08-09T12:00:00.000Z",
    updatedAt: "2026-08-09T12:00:00.000Z",
    ...overrides,
  } as LocalRdoRecord;
}

beforeEach(async () => {
  userId = crypto.randomUUID();
  setSession({
    colaboradorId: userId,
    nome: "Paulo",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);
  api.fetch.mockReset();
  api.autoritativo.mockReset();
  api.obras.mockReset();
  api.obras.mockResolvedValue([{ id: OBRA_ID }]);
  api.autoritativo.mockResolvedValue({
    kind: "FOUND",
    version: 4,
    rdo: {
      id: RDO_REMOTO,
      obraId: OBRA_ID,
      numeroRdo: "RDO-0017",
      dataRdo: "2026-08-09",
      status: "ENVIADO",
      servicosExecutados: [
        { trechoInicial: "206,822", trechoFinal: "207,100" },
      ],
    },
  });
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

describe("RDOs do servidor no aparelho de quem tem acesso", () => {
  it("traz para o aparelho o RDO que outra pessoa criou", async () => {
    respondeComLista([resumo()]);

    const resultado = await reconciliarRdosDoServidor();

    expect(resultado.descobertos).toBe(1);
    const database = await getCortexDb();
    expect(await database.get("rdos", RDO_REMOTO)).toMatchObject({
      numeroRdo: "RDO-0017",
      obraId: OBRA_ID,
      syncStatus: "SYNCED",
    });
  });

  it("busca o conteúdo, não só o cabeçalho", async () => {
    respondeComLista([resumo()]);

    const resultado = await reconciliarRdosDoServidor();

    expect(resultado.detalhados).toBe(1);
    const database = await getCortexDb();
    const guardado = await database.get("rdos", RDO_REMOTO);
    expect(guardado?.versaoEntidade).toBe(4);
    expect(
      (guardado?.payload as Record<string, unknown>).servicosExecutados,
    ).toHaveLength(1);
  });

  /*
   * Apagar tem de significar a mesma coisa nos dois caminhos.
   *
   * A lista esconde o RDO apagado por `canceladoEm`, não pelo status. O
   * cabeçalho reconciliado trazia o status certo e o carimbo vazio, então todo
   * RDO apagado voltava para a primeira página a cada abertura da tela, como
   * se estivesse vivo — e voltava de novo depois de apagado outra vez.
   */
  it("carimba o RDO que o servidor diz apagado, para ele não voltar à lista", async () => {
    respondeComLista([resumo({ status: "CANCELADA" })]);
    api.autoritativo.mockResolvedValue({
      kind: "FOUND",
      version: 4,
      rdo: {
        id: RDO_REMOTO,
        obraId: OBRA_ID,
        numeroRdo: "RDO-0017",
        dataRdo: "2026-08-09",
        status: "CANCELADA",
      },
    });

    await reconciliarRdosDoServidor();

    const database = await getCortexDb();
    const guardado = await database.get("rdos", RDO_REMOTO);
    expect(guardado?.statusRdo).toBe("CANCELADA");
    expect(guardado?.canceladoEm).toBe("2026-08-09T18:00:00.000Z");
  });

  /*
   * O outro lado: cancelar no servidor um RDO que este aparelho já conhecia
   * vivo chegava pelo caminho do detalhe, com o status novo e o carimbo antigo
   * — vazio. O efeito era o mesmo, por outra porta.
   */
  it("carimba também quando o RDO era vivo aqui e foi apagado lá", async () => {
    const database = await getCortexDb();
    await database.put("rdos", {
      id: RDO_REMOTO,
      obraId: OBRA_ID,
      programacaoId: null,
      numeroRdo: "RDO-0017",
      dataRdo: "2026-08-09",
      statusRdo: "ENVIADO",
      canceladoEm: null,
      syncStatus: "SYNCED",
      versaoEntidade: 3,
      payload: { id: RDO_REMOTO, obraId: OBRA_ID },
      createdAt: "2026-08-09T12:00:00.000Z",
      updatedAt: "2026-08-09T12:00:00.000Z",
    } as LocalRdoRecord);
    respondeComLista([resumo({ status: "CANCELADA" })]);
    api.autoritativo.mockResolvedValue({
      kind: "FOUND",
      version: 5,
      rdo: {
        id: RDO_REMOTO,
        obraId: OBRA_ID,
        numeroRdo: "RDO-0017",
        dataRdo: "2026-08-09",
        status: "CANCELADA",
      },
    });

    await reconciliarRdosDoServidor();

    const guardado = await (await getCortexDb()).get("rdos", RDO_REMOTO);
    expect(guardado?.statusRdo).toBe("CANCELADA");
    expect(guardado?.canceladoEm).toBe("2026-08-09T18:00:00.000Z");
  });

  /* Restaurar é o caminho de volta, e limpa o carimbo. */
  it("tira o carimbo quando o RDO volta a valer no servidor", async () => {
    const database = await getCortexDb();
    await database.put("rdos", {
      id: RDO_REMOTO,
      obraId: OBRA_ID,
      programacaoId: null,
      numeroRdo: "RDO-0017",
      dataRdo: "2026-08-09",
      statusRdo: "CANCELADA",
      canceladoEm: "2026-08-09T15:00:00.000Z",
      syncStatus: "SYNCED",
      versaoEntidade: 3,
      payload: { id: RDO_REMOTO, obraId: OBRA_ID },
      createdAt: "2026-08-09T12:00:00.000Z",
      updatedAt: "2026-08-09T12:00:00.000Z",
    } as LocalRdoRecord);
    respondeComLista([resumo()]);

    await reconciliarRdosDoServidor();

    const guardado = await (await getCortexDb()).get("rdos", RDO_REMOTO);
    expect(guardado?.statusRdo).toBe("ENVIADO");
    expect(guardado?.canceladoEm).toBeNull();
  });

  /*
   * A trava que mais importa. Registro pendente é o apontamento do dia de
   * alguém que ainda não subiu — e o servidor, por definição, não sabe dele.
   * Escrever por cima apagaria trabalho de campo.
   */
  it("não escreve por cima do RDO que ainda não subiu", async () => {
    const database = await getCortexDb();
    await database.put(
      "rdos",
      rdoLocal({
        syncStatus: "PENDING_SYNC",
        payload: { observacoes: "apontamento da manhã, ainda no aparelho" },
      }),
    );
    respondeComLista([resumo()]);

    await reconciliarRdosDoServidor();

    const guardado = await database.get("rdos", RDO_REMOTO);
    expect(guardado?.payload).toEqual({
      observacoes: "apontamento da manhã, ainda no aparelho",
    });
    expect(guardado?.syncStatus).toBe("PENDING_SYNC");
    expect(api.autoritativo).not.toHaveBeenCalled();
  });

  it("também não toca no que está em conflito nem no que falhou", async () => {
    const database = await getCortexDb();
    for (const estado of ["CONFLICT", "ERROR", "LOCAL_ONLY"] as const) {
      await database.put(
        "rdos",
        rdoLocal({ id: `${RDO_REMOTO}-${estado}`, syncStatus: estado }),
      );
    }
    respondeComLista(
      (["CONFLICT", "ERROR", "LOCAL_ONLY"] as const).map((estado) =>
        resumo({ id: `${RDO_REMOTO}-${estado}` }),
      ),
    );

    const resultado = await reconciliarRdosDoServidor();

    expect(resultado.descobertos).toBe(0);
    expect(resultado.detalhados).toBe(0);
  });

  /*
   * A segunda trava: ausência na resposta não é remoção. Uma lista incompleta
   * — por filtro, por obra que saiu do escopo, por falha parcial — levaria
   * embora documentos que continuam existindo.
   */
  it("não apaga o que o servidor deixou de listar", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoLocal({ id: "rdo-que-o-servidor-nao-listou" }));
    respondeComLista([]);

    await reconciliarRdosDoServidor();

    expect(
      await database.get("rdos", "rdo-que-o-servidor-nao-listou"),
    ).toBeTruthy();
  });

  /*
   * Sem rede a tela continua servindo o que já está no aparelho — o contrato
   * do modo offline. A falha não pode subir e derrubar a abertura da lista.
   */
  it("devolve o que conseguiu quando a obra não responde", async () => {
    api.fetch.mockRejectedValue(new Error("sem rede"));

    await expect(reconciliarRdosDoServidor()).resolves.toMatchObject({
      descobertos: 0,
      detalhados: 0,
    });
  });

  it("não busca nada quando a pessoa não alcança obra nenhuma", async () => {
    api.obras.mockResolvedValue([]);

    await reconciliarRdosDoServidor();

    expect(api.fetch).not.toHaveBeenCalled();
  });

  /*
   * Releitura não repete trabalho: o que já tem conteúdo e não mudou no
   * servidor não é buscado de novo.
   */
  it("não rebusca o conteúdo que já está no aparelho e não mudou", async () => {
    const database = await getCortexDb();
    await database.put(
      "rdos",
      rdoLocal({ updatedAt: "2026-08-09T18:00:00.000Z", versaoEntidade: 4 }),
    );
    respondeComLista([resumo()]);

    const resultado = await reconciliarRdosDoServidor();

    expect(resultado.detalhados).toBe(0);
    expect(api.autoritativo).not.toHaveBeenCalled();
  });

  it("rebusca quando o servidor diz que o RDO mudou depois", async () => {
    const database = await getCortexDb();
    await database.put(
      "rdos",
      rdoLocal({ updatedAt: "2026-08-09T12:00:00.000Z", versaoEntidade: 4 }),
    );
    respondeComLista([
      resumo({ atualizadoEm: "2026-08-09T20:00:00.000Z" }),
    ]);

    const resultado = await reconciliarRdosDoServidor();

    expect(resultado.detalhados).toBe(1);
  });
});

/**
 * O RDO apagado numa máquina precisa morrer em todas.
 *
 * <p>Apagar é exclusão de verdade no servidor — a linha some — e o aparelho de
 * quem apagou se limpa na hora. Os outros ficavam com a cópia para sempre: o
 * evento RDO_APAGADO já tinha passado pelo cursor deles antes de existir
 * tratamento, e a reconciliação, por regra correta, não apaga pelo que a
 * listagem deixou de dizer. O que ela faz agora é perguntar: ausente da lista,
 * o RDO é buscado pelo id, e só o 404 nominal do servidor o remove.
 */
describe("o RDO apagado em outra máquina sai deste aparelho", () => {
  it("remove quando o servidor confirma, nominalmente, que ele não existe", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoLocal());
    respondeComLista([]);
    api.autoritativo.mockResolvedValue({ kind: "MISSING" });

    const resultado = await reconciliarRdosDoServidor();

    expect(resultado.removidos).toBe(1);
    expect(await database.get("rdos", RDO_REMOTO)).toBeUndefined();
  });

  /*
   * A trava antiga continua valendo por inteiro: ausência na listagem, com o
   * documento ainda respondendo pelo id, não remove nada. É o que protege
   * contra a lista que chegou manca.
   */
  it("mantém o registro quando o id ainda responde no servidor", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoLocal());
    respondeComLista([]);

    const resultado = await reconciliarRdosDoServidor();

    expect(resultado.removidos).toBe(0);
    expect(await database.get("rdos", RDO_REMOTO)).toBeTruthy();
  });

  it("mantém o registro quando a confirmação falha", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoLocal());
    respondeComLista([]);
    api.autoritativo.mockRejectedValue(new Error("sem rede"));

    const resultado = await reconciliarRdosDoServidor();

    expect(resultado.removidos).toBe(0);
    expect(await database.get("rdos", RDO_REMOTO)).toBeTruthy();
  });

  /*
   * Trabalho de campo nunca entra na varredura. Um registro pendente é
   * apontamento que ainda não subiu, e o destino dele é decidido pela fila de
   * envio — não por uma limpeza de leitura.
   */
  it("não confirma nem remove o que tem trabalho local pendente", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoLocal({ syncStatus: "PENDING_SYNC" }));
    respondeComLista([]);

    const resultado = await reconciliarRdosDoServidor();

    expect(resultado.removidos).toBe(0);
    expect(api.autoritativo).not.toHaveBeenCalled();
    expect(await database.get("rdos", RDO_REMOTO)).toBeTruthy();
  });
});
