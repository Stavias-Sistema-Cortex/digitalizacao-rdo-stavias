import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import { databaseNameForScope } from "../db/localDataNamespace";
import { updateSyncState } from "../db/syncStateRepository";
import {
  applyPushResultAtomically,
  markMutationAsSyncing,
} from "./syncStorage";

const COLABORADOR_ID = "00000000-0000-4000-8000-0000000004a1";
const OBRA_ID = "00000000-0000-4000-8000-0000000004a2";
const EQUIPE_ID = "00000000-0000-4000-8000-0000000004a3";
const MUTATION_ID = "00000000-0000-4000-8000-0000000004a4";
const DEVICE_ID = "00000000-0000-4000-8000-0000000004a5";

let databaseName = "";

function sessao() {
  setSession({
    colaboradorId: COLABORADOR_ID,
    nome: "Alfa",
    papelAcesso: "ALFA",
    escopoGlobal: true,
    obraIds: [],
    expiraEm: "2099-01-01T00:00:00.000Z",
  });
}

async function semearEquipeEMutacao(versaoLocal: number) {
  const database = await getCortexDb();
  await database.put("teams", {
    id: EQUIPE_ID,
    obraPrincipalId: OBRA_ID,
    obraNome: "Quarta intervenção",
    nome: "FR Conservação",
    descricao: "",
    status: "ATIVA",
    inicioValidadeEm: "2026-08-01",
    fimValidadeEm: null,
    versaoEntidade: versaoLocal,
    atualizadoEm: "2026-08-05T12:00:00.000Z",
    syncStatus: "PENDING_SYNC",
    ultimoErro: null,
    pendingMutationId: MUTATION_ID,
  } as never);

  const mutation = {
    clientMutationId: MUTATION_ID,
    entidadeTipo: "EQUIPE",
    entidadeId: EQUIPE_ID,
    obraId: OBRA_ID,
    operacao: "TRANSITION",
    status: "PENDING",
    baseVersao: versaoLocal,
    tentativas: 0,
    retryAttempt: 0,
    nextAttemptAt: null,
    createdAt: "2026-08-05T12:00:00.000Z",
    updatedAt: "2026-08-05T12:00:00.000Z",
    payload: {},
  } as never;
  await database.put("outbox_mutations", mutation);
  return mutation;
}

describe("a equipe aprende a versão que o servidor devolve", () => {
  beforeEach(async () => {
    vi.stubGlobal("window", new EventTarget());
    sessao();
    databaseName = await databaseNameForScope(COLABORADOR_ID, "ALFA:global");
    await updateSyncState({ deviceId: DEVICE_ID });
  });

  afterEach(async () => {
    await closeCortexDb();
    if (databaseName) await deleteDB(databaseName);
    clearSession();
  });

  /*
   * O defeito que este teste fixa: a loja `teams` não entrava na transação de
   * sincronização, então a versão devolvida pelo servidor não tinha onde ser
   * gravada. O registro ficava no zero com que nasce, toda mutação subia com
   * baseVersao zero contra um servidor já adiante, e cada recusa virava um
   * cartão "Equipe arquivada — Rejeitada" na tela de conflitos. Repetir o gesto
   * repetia o cartão.
   */
  it("grava a versão do servidor em vez de continuar no zero", async () => {
    const mutation = await semearEquipeEMutacao(0);
    await markMutationAsSyncing(mutation);

    await applyPushResultAtomically({
      clientMutationId: MUTATION_ID,
      status: "APLICADA",
      resultado: { versaoEntidade: 3 },
    } as never);

    const database = await getCortexDb();
    expect(await database.get("teams", EQUIPE_ID)).toMatchObject({
      versaoEntidade: 3,
      syncStatus: "SYNCED",
    });
  });

  /*
   * pendingMutationId é o que diz à tela que há escrita em voo. Deixá-lo
   * apontando para uma mutação já aplicada manteria a equipe eternamente
   * "sincronizando", que é o mesmo sintoma por outro caminho.
   */
  it("solta a mutação em voo quando ela termina", async () => {
    const mutation = await semearEquipeEMutacao(0);
    await markMutationAsSyncing(mutation);

    await applyPushResultAtomically({
      clientMutationId: MUTATION_ID,
      status: "APLICADA",
      resultado: { versaoEntidade: 3 },
    } as never);

    const database = await getCortexDb();
    expect(
      (await database.get("teams", EQUIPE_ID))?.pendingMutationId,
    ).toBeNull();
  });

  /*
   * Sem versão no recibo, a local é preservada. Sobrescrever com nulo seria
   * trocar um número desatualizado por nenhum — e nenhum volta a valer zero na
   * próxima soma.
   */
  it("preserva a versão local quando o recibo não traz nenhuma", async () => {
    const mutation = await semearEquipeEMutacao(7);
    await markMutationAsSyncing(mutation);

    await applyPushResultAtomically({
      clientMutationId: MUTATION_ID,
      status: "APLICADA",
      resultado: {},
    } as never);

    const database = await getCortexDb();
    expect(
      (await database.get("teams", EQUIPE_ID))?.versaoEntidade,
    ).toBe(7);
  });
  /*
   * O defeito que o usuário via como "a sincronização não roda": três pessoas
   * adicionadas à mesma equipe antes de qualquer sincronização produzem três
   * mutações com a MESMA versão-base. A primeira aplica e leva a equipe adiante;
   * as outras duas chegam com base vencida e voltam como conflito. O painel
   * mostrava três membros, o cartão mostrava zero, e nada dizia que o segundo
   * gesto nunca teve chance.
   *
   * Reapoiar é seguro aqui porque a versão nova veio do gesto anterior desta
   * mesma pessoa, neste mesmo aparelho — quem fez a segunda edição já sabia da
   * primeira. Conflito de verdade vem de outro aparelho, pelo pull, e continua
   * exigindo decisão.
   */
  it("reapoia as edições irmãs que ainda esperam na fila", async () => {
    const mutation = await semearEquipeEMutacao(0);
    const database = await getCortexDb();
    await database.put("outbox_mutations", {
      ...(mutation as unknown as Record<string, unknown>),
      clientMutationId: "00000000-0000-4000-8000-0000000004b1",
      status: "PENDING",
      baseVersao: 0,
    } as never);
    await markMutationAsSyncing(mutation);

    await applyPushResultAtomically({
      clientMutationId: MUTATION_ID,
      status: "APLICADA",
      resultado: { versaoEntidade: 3 },
    } as never);

    expect(
      (await database.get(
        "outbox_mutations",
        "00000000-0000-4000-8000-0000000004b1",
      ))?.baseVersao,
    ).toBe(3);
  });

  /*
   * A irmã que já está adiante não retrocede. Reescrever cegamente devolveria
   * uma versão-base menor do que a que ela já tinha, e o servidor recusaria por
   * conta de um "conserto".
   */
  it("não faz a irmã retroceder para uma versão anterior à dela", async () => {
    const mutation = await semearEquipeEMutacao(0);
    const database = await getCortexDb();
    await database.put("outbox_mutations", {
      ...(mutation as unknown as Record<string, unknown>),
      clientMutationId: "00000000-0000-4000-8000-0000000004b2",
      status: "PENDING",
      baseVersao: 9,
    } as never);
    await markMutationAsSyncing(mutation);

    await applyPushResultAtomically({
      clientMutationId: MUTATION_ID,
      status: "APLICADA",
      resultado: { versaoEntidade: 3 },
    } as never);

    expect(
      (await database.get(
        "outbox_mutations",
        "00000000-0000-4000-8000-0000000004b2",
      ))?.baseVersao,
    ).toBe(9);
  });
});
