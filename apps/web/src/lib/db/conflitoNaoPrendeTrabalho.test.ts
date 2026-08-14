import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import { createEmptyRdo } from "../../features/rdos/createEmptyRdo";
import { closeCortexDb, getCortexDb } from "./cortexDb";
import type {
  LocalRdoRecord,
  OperationalEventRecord,
  OutboxMutationRecord,
} from "./db.types";
import { databaseNameForScope } from "./localDataNamespace";
import { saveExistingRdoDraftAtomically } from "./localRdoService";

const OBRA_ID = "00000000-0000-4000-8000-000000000901";
const RDO_ID = "00000000-0000-4000-8000-000000000902";
const CONFLITADA_ID = "00000000-0000-4000-8000-000000000903";
const EVENTO_ID = "00000000-0000-4000-8000-000000000904";
const EM_VOO_ID = "00000000-0000-4000-8000-000000000905";
const OCORRIDO_EM = "2026-07-25T11:00:00.000Z";

/** A versão que o servidor informou no conflito e o registro local adotou. */
const VERSAO_DO_SERVIDOR = 9;
/** A versão contra a qual a edição recusada tinha sido montada. */
const VERSAO_QUE_ENVELHECEU = 7;

let userId = "";
let databaseName = "";

function session() {
  return {
    colaboradorId: userId,
    nome: "Encarregado",
    papelAcesso: "BETA" as const,
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  };
}

function rdoEmConflito(
  versaoEntidade: number | null = VERSAO_DO_SERVIDOR,
): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-0902",
    dataRdo: "2026-07-25",
    statusRdo: "RASCUNHO",
    syncStatus: "CONFLICT",
    versaoEntidade,
    payload: { observacoes: "o que o servidor devolveu no conflito" },
    createdAt: OCORRIDO_EM,
    updatedAt: OCORRIDO_EM,
  };
}

function mutacaoConflitada(): OutboxMutationRecord {
  return {
    clientMutationId: CONFLITADA_ID,
    entidadeTipo: "RDO",
    entidadeId: RDO_ID,
    operacao: "ATUALIZAR_RDO_RASCUNHO",
    baseVersao: VERSAO_QUE_ENVELHECEU,
    payload: {
      observacoes: "a medição que o servidor recusou",
      operationalEvents: [{ id: EVENTO_ID }],
    },
    status: "CONFLICT",
    tentativas: 1,
    ultimaTentativaEm: OCORRIDO_EM,
    ultimoErro: "Conflito de versão.",
    conflito: { versaoAtual: VERSAO_DO_SERVIDOR },
    criadaNoClienteEm: OCORRIDO_EM,
    updatedAt: OCORRIDO_EM,
  };
}

function eventoDaConflitada(): OperationalEventRecord {
  return {
    id: EVENTO_ID,
    schemaVersion: 1,
    tipo: "RDO_EDITADO",
    ocorridoEm: OCORRIDO_EM,
    occurredAt: OCORRIDO_EM,
    syncedAt: null,
    origin: "OFFLINE",
    obraId: OBRA_ID,
    colaboradorId: userId,
    principalEntity: { tipo: "RDO", id: RDO_ID, nome: "RDO-0902" },
    relatedEntities: [],
    payload: { observacoes: "a medição que o servidor recusou" },
    syncStatus: "PENDING_SYNC",
    result: "PENDING",
    clientMutationId: CONFLITADA_ID,
  } as unknown as OperationalEventRecord;
}

function correcaoDoUsuario() {
  const draft = createEmptyRdo();
  draft.id = RDO_ID;
  draft.obraId = OBRA_ID;
  draft.numeroRdo = "RDO-0902";
  draft.dataRdo = "2026-07-25";
  draft.observacoes = "a medição refeita por cima do que veio do servidor";
  return draft;
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  userId = crypto.randomUUID();
  setSession(session());
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/**
 * O conflito não pode confiscar o que a pessoa digitou.
 *
 * <p>`rdoAfterConflict` adota a versão atual do servidor no registro local
 * justamente para que a próxima edição recupere a sincronização. Recusar essa
 * edição prendia a correção no formulário: sem poder ir para o banco, sem
 * existir em outro lugar, e com o descarte — que joga fora exatamente esse
 * trabalho — como única saída oferecida.
 */
describe("o conflito não prende o trabalho de quem está editando", () => {
  it("aceita a correção e a apoia na versão do servidor", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoEmConflito());
    await database.put("outbox_mutations", mutacaoConflitada());

    const { mutation } = await saveExistingRdoDraftAtomically(
      correcaoDoUsuario(),
    );

    expect(mutation.baseVersao).toBe(VERSAO_DO_SERVIDOR);
    expect(mutation.status).toBe("PENDING");
    expect(mutation.clientMutationId).not.toBe(CONFLITADA_ID);
  });

  it("tira o RDO do conflito e deixa a fila com uma linha viva só", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoEmConflito());
    await database.put("outbox_mutations", mutacaoConflitada());

    await saveExistingRdoDraftAtomically(correcaoDoUsuario());

    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      syncStatus: "PENDING_SYNC",
      versaoEntidade: VERSAO_DO_SERVIDOR,
    });
    expect(
      await database.get("outbox_mutations", CONFLITADA_ID),
    ).toBeUndefined();

    const naFila = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      RDO_ID,
    );
    expect(naFila).toHaveLength(1);
    expect(naFila[0].status).toBe("PENDING");
  });

  /*
   * Sai a intenção, fica a evidência — e ela precisa contar a história certa.
   * "Descartado" diria que a pessoa abriu mão do trabalho; ela não abriu, ela
   * o reescreveu por cima do que o servidor mandou.
   */
  it("marca o rastro como substituído, e não como descartado", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoEmConflito());
    await database.put("outbox_mutations", mutacaoConflitada());
    await database.put("operational_events", eventoDaConflitada());

    await saveExistingRdoDraftAtomically(correcaoDoUsuario());

    expect(await database.get("operational_events", EVENTO_ID)).toMatchObject({
      result: "SUPERSEDED",
      syncStatus: "LOCAL_ONLY",
      errorCategory: "SUPERSEDED_BY_LOCAL_EDIT",
    });
  });

  /*
   * Sem versão do servidor a edição subiria com a mesma base que já conflitou,
   * e o RDO voltaria ao conflito no ciclo seguinte. Recusar aqui continua
   * certo — o que mudou é que a recusa agora diz o que fazer.
   */
  it("recusa, dizendo o que fazer, quando não se sabe a versão do servidor", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoEmConflito(null));
    await database.put("outbox_mutations", {
      ...mutacaoConflitada(),
      baseVersao: null,
    });

    await expect(
      saveExistingRdoDraftAtomically(correcaoDoUsuario()),
    ).rejects.toThrow(/Sincronize para receber a versão do servidor/);

    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      syncStatus: "CONFLICT",
    });
    expect(
      await database.get("outbox_mutations", CONFLITADA_ID),
    ).toBeDefined();
  });

  /*
   * A linha em voo não é do impasse: o resultado dela ainda vai chegar, e o
   * motor precisa achá-la para casar esse resultado. Aposentá-la aqui seria
   * apagar envio alheio a pretexto de limpar o conflito.
   */
  it("não aposenta a linha que está no ar", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoEmConflito());
    await database.put("outbox_mutations", mutacaoConflitada());
    await database.put("outbox_mutations", {
      ...mutacaoConflitada(),
      clientMutationId: EM_VOO_ID,
      status: "SYNCING",
      payload: { observacoes: "já entregue ao servidor" },
    });

    await saveExistingRdoDraftAtomically(correcaoDoUsuario());

    expect(
      await database.get("outbox_mutations", EM_VOO_ID),
    ).toMatchObject({ status: "SYNCING" });
    expect(
      await database.get("outbox_mutations", CONFLITADA_ID),
    ).toBeUndefined();
  });
});
