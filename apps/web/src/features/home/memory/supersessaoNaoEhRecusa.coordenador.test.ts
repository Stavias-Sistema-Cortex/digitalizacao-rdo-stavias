import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../../auth/authSession";
import { closeCortexDb, getCortexDb } from "../../../lib/db/cortexDb";
import type {
  LocalRdoRecord,
  OutboxMutationRecord,
} from "../../../lib/db/db.types";
import { databaseNameForScope } from "../../../lib/db/localDataNamespace";
import {
  commitLocalMutation,
  type LocalMutationCommand,
  type LocalMutationDomainWrite,
} from "../../../lib/sync/localMutationCoordinator";
import { foiSubstituida } from "../../../lib/sync/superacaoDeMutacao";
import { localEventToSearchDocument } from "./memorySearchDocument";

const OBRA_ID = "00000000-0000-4000-8000-000000000901";
const DEVICE_ID = "00000000-0000-4000-8000-000000000902";
const RDO_ID = "00000000-0000-4000-8000-000000000903";
const PRIMEIRO_ENVELOPE = "00000000-0000-4000-8000-000000000904";
const SEGUNDO_ENVELOPE = "00000000-0000-4000-8000-000000000905";
const SALVO_AS = "2026-08-03T09:00:00.000Z";
const SALVO_DE_NOVO_AS = "2026-08-03T09:04:00.000Z";

let databaseName = "";
let userId = "";
let escopo = "";

function rdo(observacoes: string): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-014",
    dataRdo: "2026-08-03",
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: null,
    payload: { observacoes },
    createdAt: SALVO_AS,
    updatedAt: SALVO_AS,
  };
}

function plano(
  record: LocalRdoRecord,
): readonly LocalMutationDomainWrite<"rdos">[] {
  return [{ store: "rdos", value: record, principal: true }];
}

function criacao(record: LocalRdoRecord): LocalMutationCommand<"rdos"> {
  return {
    clientMutationId: PRIMEIRO_ENVELOPE,
    deviceId: DEVICE_ID,
    userId,
    obraId: OBRA_ID,
    entityType: "RDO",
    entityId: record.id,
    entityName: record.numeroRdo,
    operation: "CREATE",
    transportOperation: "CRIAR_RDO",
    baseVersion: null,
    occurredAt: SALVO_AS,
    previousSnapshot: {},
    nextSnapshot: { ...record },
    eventType: "RDO_CRIADO",
    write: () => plano(record),
  };
}

/**
 * A segunda gravação continua sendo uma criação, e é assim de propósito.
 *
 * <p>`substituirRascunhoLocal` escolhe a operação por
 * `existingRdo.versaoEntidade !== null`: enquanto o rascunho nunca chegou ao
 * servidor ele não tem versão, então não há versão-base para uma atualização
 * apoiar-se — o envelope novo é outra criação, que absorve a anterior. É
 * justamente o caso deste teste, o do aparelho sem rede, e mirá-lo como
 * `UPDATE` testaria um caminho que a Stavias não percorre em campo.
 */
function segundaGravacao(
  record: LocalRdoRecord,
): LocalMutationCommand<"rdos"> {
  return {
    clientMutationId: SEGUNDO_ENVELOPE,
    deviceId: DEVICE_ID,
    userId,
    obraId: OBRA_ID,
    entityType: "RDO",
    entityId: record.id,
    entityName: record.numeroRdo,
    operation: "CREATE",
    transportOperation: "CRIAR_RDO",
    baseVersion: null,
    occurredAt: SALVO_DE_NOVO_AS,
    previousSnapshot: {},
    nextSnapshot: { ...record },
    eventType: "RDO_EDITADO",
    causationId: PRIMEIRO_ENVELOPE,
    supersedesMutationId: PRIMEIRO_ENVELOPE,
    write: () => plano(record),
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  userId = crypto.randomUUID();
  escopo = `BETA:${OBRA_ID}`;
  setSession({
    colaboradorId: userId,
    nome: "Encarregada de campo",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(userId, escopo);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/**
 * O caso reproduzido, e não descrito.
 *
 * <p>A correção que separou substituição de recusa foi verificada só do lado de
 * quem lê: os testes de projeção montavam à mão um evento já com
 * `SUPERSEDED_BY_LOCAL_EDIT` gravado e conferiam que a Memória o classificava
 * bem. Isso prende metade da corrente. Se o coordenador — quem de fato escreve
 * a marca ao trocar o envelope — parasse de escrevê-la, ou passasse a escrever
 * outra, os testes de projeção continuariam verdes e a aba voltaria a mostrar
 * cartão vermelho de "Revisão necessária" para quem só continuou preenchendo o
 * próprio RDO.
 *
 * <p>Este teste percorre o caminho inteiro uma vez: grava de verdade, grava de
 * novo por cima com o aparelho sem rede, lê do IndexedDB o que sobrou e leva
 * <em>esse</em> registro — não um fabricado — até a projeção da Memória. É o
 * caso que existia em campo, onde o RDO é salvo a cada campo preenchido.
 */
describe("segunda gravação offline do mesmo RDO", () => {
  it("marca o envelope absorvido como substituído, e não como recusado", async () => {
    await commitLocalMutation(criacao(rdo("Compactação iniciada")));
    await commitLocalMutation(
      segundaGravacao(rdo("Compactação iniciada · km 12 ao 14")),
    );

    const db = await getCortexDb();
    const absorvido = await db.get("outbox_mutations", PRIMEIRO_ENVELOPE);

    expect(absorvido).toBeDefined();
    expect(absorvido?.lastSafeCode).toBe("SUPERSEDED_BY_LOCAL_EDIT");
    expect(absorvido?.blockedReason).toBe(
      `SUPERSEDED_BY:${SEGUNDO_ENVELOPE}`,
    );
    // Nada é reagendado: o trabalho agora responde pelo envelope novo.
    expect(absorvido?.nextAttemptAt).toBeNull();
  });

  /*
   * A ponta que faltava. O evento é lido do banco depois da troca, exatamente
   * como a Memória o leria, e é ele que vai à projeção.
   */
  it("chega à Memória como histórico, sem pedir revisão de ninguém", async () => {
    await commitLocalMutation(criacao(rdo("Compactação iniciada")));
    await commitLocalMutation(
      segundaGravacao(rdo("Compactação iniciada · km 12 ao 14")),
    );

    const db = await getCortexDb();
    const eventos = await db.getAllFromIndex(
      "operational_events",
      "by-client-mutation-id",
      PRIMEIRO_ENVELOPE,
    );

    expect(eventos).toHaveLength(1);

    const documento = localEventToSearchDocument(userId, escopo, eventos[0]);

    expect(documento.syncStatus).toBe("SUPERSEDED");
    expect(documento.review).toBeNull();
  });

  /*
   * O envelope novo continua sendo trabalho a subir. Se a absorção também o
   * silenciasse, a tela diria que subiu o que não subiu — o erro caro deste
   * app.
   */
  it("deixa o envelope novo respondendo pelo trabalho", async () => {
    await commitLocalMutation(criacao(rdo("Compactação iniciada")));
    await commitLocalMutation(
      segundaGravacao(rdo("Compactação iniciada · km 12 ao 14")),
    );

    const db = await getCortexDb();
    const fila = (await db.getAll(
      "outbox_mutations",
    )) as OutboxMutationRecord[];
    const novo = fila.find(
      (item) => item.clientMutationId === SEGUNDO_ENVELOPE,
    );
    const absorvido = fila.find(
      (item) => item.clientMutationId === PRIMEIRO_ENVELOPE,
    );

    expect(novo?.status).toBe("PENDING");
    expect(foiSubstituida(novo!, fila)).toBe(false);
    // E o absorvido só é dispensável porque existe alguém vivo por ele.
    expect(foiSubstituida(absorvido!, fila)).toBe(true);
  });
});
