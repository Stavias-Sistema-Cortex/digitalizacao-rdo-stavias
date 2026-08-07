import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const lookupMocks = vi.hoisted(() => ({
  context: vi.fn(),
}));

vi.mock("../../features/rdos/rdoLookupApi", () => ({
  buscarContextoDeCriacaoRdo: lookupMocks.context,
}));

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import type {
  LocalRdoRecord,
  OutboxMutationRecord,
} from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import { hydrateBlockedRdoUpdateContextsForSync } from "../db/localRdoService";
import { selectReadyOutboxMutations } from "./outboxDependencies";

const OBRA_ID = "00000000-0000-4000-8000-000000000401";
const RDO_ID = "00000000-0000-4000-8000-000000000403";
const MUTATION_ID = "00000000-0000-4000-8000-000000000404";
const PREVIOUS_RDO_ID = "00000000-0000-4000-8000-000000000407";
const OCCURRED_AT = "2026-07-22T12:00:00.000Z";

let databaseName = "";
let userId = "";

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

/**
 * O RDO como ele fica depois da edição que se bloqueou sozinha.
 *
 * `versaoEntidade` preenchida porque o CRIAR_RDO já subiu, e
 * `creationContextVersion` ausente do payload porque a própria escrita que
 * gravou o bloqueio regravou o registro a partir de um rascunho sem recibo.
 */
function rdoEditadoSemRecibo(): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-0009",
    dataRdo: "2026-07-22",
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: 1,
    payload: { observacoes: "sete eventos de campo" },
    createdAt: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
  };
}

function edicaoBloqueada(): OutboxMutationRecord {
  return {
    clientMutationId: MUTATION_ID,
    entidadeTipo: "RDO",
    entidadeId: RDO_ID,
    operacao: "ATUALIZAR_RDO_RASCUNHO",
    baseVersao: 1,
    payload: { observacoes: "sete eventos de campo" },
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    blockedReason: "RDO_CREATION_CONTEXT_REQUIRED",
    criadaNoClienteEm: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
  };
}

function completeCoverage() {
  return { status: "COMPLETE", total: 0, returned: 0, complete: true };
}

function notConfigured() {
  return { status: "NOT_CONFIGURED", total: 0, returned: 0, complete: false };
}

function context() {
  return {
    data: "2026-07-22",
    previousRdo: { id: PREVIOUS_RDO_ID },
    previousWorkforce: [],
    programacoes: [],
    colaboradores: [],
    equipamentos: [],
    coverage: {
      previousWorkforce: completeCoverage(),
      programacoes: completeCoverage(),
      colaboradores: completeCoverage(),
      equipamentos: completeCoverage(),
      serviceCatalog: notConfigured(),
      priceCatalog: notConfigured(),
    },
    freshness: {
      status: "FRESH",
      sourceVersion: 12,
      generatedAt: OCCURRED_AT,
      staleAfter: "2026-07-22T12:15:00.000Z",
    },
    provenance: {
      receiptVersion: 50,
      sourceVersion: 12,
      worksiteId: OBRA_ID,
      selectedDate: "2026-07-22",
      previousRdoId: PREVIOUS_RDO_ID,
      generatedAt: OCCURRED_AT,
    },
  };
}

async function semear(): Promise<void> {
  const database = await getCortexDb();
  await database.put("rdos", rdoEditadoSemRecibo());
  await database.put("outbox_mutations", edicaoBloqueada());
}

beforeEach(async () => {
  userId = crypto.randomUUID();
  setSession(session());
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);
  lookupMocks.context.mockReset();
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

/*
 * A edição de rascunho que nascia presa e não tinha quem a soltasse.
 *
 * `atualizarRdoLocal` decide o bloqueio contra o RDO de antes da escrita, e a
 * mesma transação regrava o registro a partir do rascunho — sem o recibo, que
 * foi o motivo do bloqueio. O veredito perde a prova no instante em que é
 * escrito, e a hidratação de contexto só olhava `CRIAR_RDO`. A linha ficava
 * PENDING com `blockedReason`, `selectReadyOutboxMutations` a descartava, e a
 * tela prometia envio automático para algo que ninguém buscaria nunca.
 */
describe("edição de rascunho presa pelo recibo de contexto", () => {
  it("busca o recibo, carimba no envelope e devolve a edição à fila", async () => {
    await semear();
    lookupMocks.context.mockResolvedValue(context());

    const antes = await getCortexDb();
    expect(
      selectReadyOutboxMutations(
        await antes.getAll("outbox_mutations"),
        10,
      ),
    ).toEqual([]);

    expect(await hydrateBlockedRdoUpdateContextsForSync()).toBe(1);

    const database = await getCortexDb();
    const mutation = await database.get("outbox_mutations", MUTATION_ID);
    expect(mutation).toMatchObject({
      status: "PENDING",
      blockedReason: null,
      nextAttemptAt: null,
    });
    // O recibo ficou registrado, e não apenas o bloqueio removido: a edição
    // sobe cumprindo a regra, em vez de driblá-la.
    const rdo = await database.get("rdos", RDO_ID);
    expect(rdo?.payload).toMatchObject({
      creationContextVersion: 50,
      previousRdoId: PREVIOUS_RDO_ID,
    });
    expect(
      selectReadyOutboxMutations(
        await database.getAll("outbox_mutations"),
        10,
      ).map((item) => item.clientMutationId),
    ).toEqual([MUTATION_ID]);
  });

  /*
   * Sem rede não se inventa recibo. O que não pode acontecer é a linha
   * continuar parada sem espera marcada — foi assim que ela morreu calada.
   */
  it("mantém a espera visível quando o recibo não vem", async () => {
    await semear();
    lookupMocks.context.mockRejectedValue(new Error("sem rede"));

    expect(await hydrateBlockedRdoUpdateContextsForSync()).toBe(0);

    const database = await getCortexDb();
    const mutation = await database.get("outbox_mutations", MUTATION_ID);
    expect(mutation?.blockedReason).toBe("RDO_CREATION_CONTEXT_REQUIRED");
    expect(mutation?.nextAttemptAt).toEqual(expect.any(String));
    expect(mutation?.ultimoErro).toContain("tentará novamente");
  });

  /*
   * Recibo que não satisfaz a regra não vira liberação: soltar assim entregaria
   * ao servidor um envelope que ele recusaria, trocando um impasse mudo por uma
   * recusa em laço.
   */
  it("não libera com recibo que a regra não aceita", async () => {
    await semear();
    lookupMocks.context.mockResolvedValue({
      ...context(),
      freshness: { ...context().freshness, status: "STALE" },
    });

    expect(await hydrateBlockedRdoUpdateContextsForSync()).toBe(0);

    const database = await getCortexDb();
    const mutation = await database.get("outbox_mutations", MUTATION_ID);
    expect(mutation?.blockedReason).toBe("RDO_CREATION_CONTEXT_REQUIRED");
    expect(
      selectReadyOutboxMutations(
        await database.getAll("outbox_mutations"),
        10,
      ),
    ).toEqual([]);
  });

  it("não mexe no RDO que já está sincronizado", async () => {
    const database = await getCortexDb();
    await database.put("rdos", {
      ...rdoEditadoSemRecibo(),
      syncStatus: "SYNCED",
    });
    await database.put("outbox_mutations", edicaoBloqueada());
    lookupMocks.context.mockResolvedValue(context());

    expect(await hydrateBlockedRdoUpdateContextsForSync()).toBe(0);
    expect(lookupMocks.context).not.toHaveBeenCalled();
  });
});
