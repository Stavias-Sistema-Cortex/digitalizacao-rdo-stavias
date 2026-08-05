import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import { databaseNameForScope } from "../db/localDataNamespace";
import { updateSyncState } from "../db/syncStateRepository";
import { podarMutacoesJaAplicadas } from "./syncStorage";

const COLABORADOR_ID = "00000000-0000-4000-8000-0000000007d1";
const OBRA_ID = "00000000-0000-4000-8000-0000000007d2";
const DEVICE_ID = "00000000-0000-4000-8000-0000000007d3";

let databaseName = "";

async function semear(
  id: string,
  status: string,
  extras: Record<string, unknown> = {},
) {
  const database = await getCortexDb();
  await database.put("outbox_mutations", {
    clientMutationId: id,
    entidadeTipo: "RDO",
    entidadeId: `entidade-${id}`,
    obraId: OBRA_ID,
    operacao: "ATUALIZAR_RDO_RASCUNHO",
    status,
    baseVersao: 1,
    tentativas: 1,
    retryAttempt: 0,
    nextAttemptAt: null,
    blockedReason: null,
    createdAt: "2026-08-05T12:00:00.000Z",
    updatedAt: "2026-08-05T12:00:00.000Z",
    payload: {},
    ...extras,
  } as never);
}

async function identificadoresNaFila(): Promise<string[]> {
  const database = await getCortexDb();
  return (await database.getAll("outbox_mutations"))
    .map((mutation) => mutation.clientMutationId)
    .sort();
}

describe("podar da outbox o que já subiu", () => {
  beforeEach(async () => {
    vi.stubGlobal("window", new EventTarget());
    setSession({
      colaboradorId: COLABORADOR_ID,
      nome: "Alfa",
      papelAcesso: "ALFA",
      escopoGlobal: true,
      obraIds: [],
      expiraEm: "2099-01-01T00:00:00.000Z",
    });
    databaseName = await databaseNameForScope(COLABORADOR_ID, "ALFA:global");
    await updateSyncState({ deviceId: DEVICE_ID });
    // deleteDB no afterEach não zera a base entre os casos deste arquivo, e
    // linha vazada de um caso vira asserção falsa no seguinte. A poda é
    // justamente sobre o que sobra na fila: aqui, começar vazio é o pré-
    // requisito de todo o resto.
    await (await getCortexDb()).clear("outbox_mutations");
  });

  afterEach(async () => {
    await closeCortexDb();
    if (databaseName) await deleteDB(databaseName);
    clearSession();
  });

  /*
   * O defeito: nada apagava linha aplicada. Numa fila real havia 963 mutações,
   * das quais cerca de 200 já tinham subido e continuavam ali — e toda varredura
   * da fila é getAll, então o peso é de cada ciclo, não só do armazenamento.
   */
  it("apaga o que já subiu", async () => {
    await semear("m-1", "SYNCED");
    await semear("m-2", "SYNCED");

    expect(await podarMutacoesJaAplicadas()).toBe(2);
    expect(await identificadoresNaFila()).toEqual([]);
  });

  it("não toca no que ainda não subiu", async () => {
    await semear("m-1", "PENDING");
    await semear("m-2", "SYNCING");
    await semear("m-3", "CONFLICT");
    await semear("m-4", "REJECTED");

    expect(await podarMutacoesJaAplicadas()).toBe(0);
    expect(await identificadoresNaFila()).toHaveLength(4);
  });

  /*
   * A regra que torna a poda perigosa se ignorada: uma dependência que some não
   * vira "satisfeita", vira `missingDependencies` — e o dependente fica
   * bloqueado para sempre, sem nada na tela explicando por quê.
   */
  it("preserva a aplicada de que alguém ainda depende", async () => {
    await semear("m-1", "SYNCED");
    await semear("m-2", "PENDING", { dependsOnMutationIds: ["m-1"] });

    expect(await podarMutacoesJaAplicadas()).toBe(0);
    expect(await identificadoresNaFila()).toEqual(["m-1", "m-2"]);
  });

  it("preserva a aplicada citada como substituída", async () => {
    await semear("m-1", "SYNCED");
    await semear("m-2", "REJECTED", { blockedReason: "SUPERSEDED_BY:m-1" });

    expect(await podarMutacoesJaAplicadas()).toBe(0);
  });

  it("preserva a aplicada citada por causalidade", async () => {
    await semear("m-1", "SYNCED");
    await semear("m-2", "PENDING", { causationId: "m-1" });

    expect(await podarMutacoesJaAplicadas()).toBe(0);
  });

  /*
   * Uma citação entre duas que vão embora não preserva nenhuma das duas: as
   * duas saem na mesma transação, e a citação sai junto. Tratar isso como
   * preservação faria a poda nunca alcançar cadeias inteiras já aplicadas —
   * exatamente as maiores.
   */
  it("apaga a cadeia inteira quando o citador também já subiu", async () => {
    await semear("m-1", "SYNCED");
    await semear("m-2", "SYNCED", { dependsOnMutationIds: ["m-1"] });

    expect(await podarMutacoesJaAplicadas()).toBe(2);
    expect(await identificadoresNaFila()).toEqual([]);
  });

  /*
   * O recibo de integração mora na própria linha da outbox: listarDesfechosIntegracao
   * lê as SOLICITACAO_INTEGRACAO já sincronizadas para mostrar o desfecho depois
   * de recarregar a página. Podá-las apagaria a tela de desfechos.
   */
  it("preserva o recibo de integração", async () => {
    await semear("m-1", "SYNCED", {
      entidadeTipo: "SOLICITACAO_INTEGRACAO",
      resultadoServidor: { estado: "EXECUTADA" },
    });

    expect(await podarMutacoesJaAplicadas()).toBe(0);
    expect(await identificadoresNaFila()).toEqual(["m-1"]);
  });

  /*
   * Sem recibo não há o que mostrar, e a linha vira peso igual às outras.
   */
  it("apaga a integração que subiu sem recibo", async () => {
    await semear("m-1", "SYNCED", {
      entidadeTipo: "SOLICITACAO_INTEGRACAO",
    });

    expect(await podarMutacoesJaAplicadas()).toBe(1);
  });

  it("não faz nada quando não há o que podar", async () => {
    await semear("m-1", "PENDING");

    expect(await podarMutacoesJaAplicadas()).toBe(0);
  });
});
