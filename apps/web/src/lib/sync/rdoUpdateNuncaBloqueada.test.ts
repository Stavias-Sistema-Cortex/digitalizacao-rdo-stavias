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
import { releaseBlockedRdoUpdatesForSync } from "../db/localRdoService";
import { selectReadyOutboxMutations } from "./outboxDependencies";

const OBRA_ID = "00000000-0000-4000-8000-000000000401";
const RDO_ID = "00000000-0000-4000-8000-000000000403";
const MUTATION_ID = "00000000-0000-4000-8000-000000000404";
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

function rdoEditado(): LocalRdoRecord {
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

/** Uma linha como as que ficaram presas nos aparelhos antes da regra sair. */
function edicaoBloqueadaNaFilaAntiga(
  overrides: Partial<OutboxMutationRecord> = {},
): OutboxMutationRecord {
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
    ...overrides,
  } as OutboxMutationRecord;
}

async function semear(
  mutation: OutboxMutationRecord = edicaoBloqueadaNaFilaAntiga(),
): Promise<void> {
  const database = await getCortexDb();
  await database.put("rdos", rdoEditado());
  await database.put("outbox_mutations", mutation);
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
 * A edição de rascunho não é mais barrada pelo recibo de contexto, porque o
 * servidor descarta o que o envelope traz nesse campo: `updateDraft` sobrescreve
 * `creationContextVersion` com o valor persistido antes de montar a requisição.
 * O bloqueio guardava um dado que ninguém lia, e cobrava o preço mais alto que
 * esta fila tem — a linha não era recusada, ela sumia.
 */
describe("edição de rascunho presa por um bloqueio que já não vale", () => {
  it("solta a linha e a devolve ao envio", async () => {
    await semear();

    const antes = await getCortexDb();
    expect(
      selectReadyOutboxMutations(
        await antes.getAll("outbox_mutations"),
        10,
      ),
    ).toEqual([]);

    expect(await releaseBlockedRdoUpdatesForSync()).toBe(1);

    const database = await getCortexDb();
    const mutation = await database.get("outbox_mutations", MUTATION_ID);
    expect(mutation).toMatchObject({
      status: "PENDING",
      blockedReason: null,
      nextAttemptAt: null,
    });
    expect(
      selectReadyOutboxMutations(
        await database.getAll("outbox_mutations"),
        10,
      ).map((item) => item.clientMutationId),
    ).toEqual([MUTATION_ID]);
  });

  /*
   * Sem rede a reparação continua valendo. Prender o conserto de um bloqueio
   * inválido à disponibilidade de rede castigaria justamente o aparelho em
   * campo, que é onde o RDO nasce.
   */
  it("solta sem tocar na rede", async () => {
    await semear();
    lookupMocks.context.mockRejectedValue(new Error("sem rede"));

    expect(await releaseBlockedRdoUpdatesForSync()).toBe(1);
    expect(lookupMocks.context).not.toHaveBeenCalled();
  });

  it("recupera também a linha que já tinha ido para ERROR", async () => {
    await semear(edicaoBloqueadaNaFilaAntiga({
      status: "ERROR",
      ultimoErro: "Contexto da obra indisponível; a sincronização tentará novamente.",
    }));

    expect(await releaseBlockedRdoUpdatesForSync()).toBe(1);

    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", MUTATION_ID))
      .toMatchObject({
        status: "PENDING",
        blockedReason: null,
        // O erro era do bloqueio. Mantê-lo faria a tela explicar a linha
        // pendente com um motivo que já não existe.
        ultimoErro: null,
      });
  });

  /*
   * Uma linha bloqueada pode sobreviver ao desfecho do seu RDO: o descarte de
   * conflito marca o registro como SYNCED sem enxergá-la. Soltar essa órfã
   * empurraria um rascunho velho por cima do estado que alguém já deu por
   * resolvido — e o servidor aceitaria, porque a versão-base ainda bate.
   */
  it("não solta a linha cujo RDO já está resolvido", async () => {
    const database = await getCortexDb();
    await database.put("rdos", { ...rdoEditado(), syncStatus: "SYNCED" });
    await database.put("outbox_mutations", edicaoBloqueadaNaFilaAntiga());

    expect(await releaseBlockedRdoUpdatesForSync()).toBe(0);
    expect(
      (await database.get("outbox_mutations", MUTATION_ID))?.blockedReason,
    ).toBe("RDO_CREATION_CONTEXT_REQUIRED");
  });

  it("não solta a linha órfã, sem RDO local", async () => {
    const database = await getCortexDb();
    await database.put("outbox_mutations", edicaoBloqueadaNaFilaAntiga());

    expect(await releaseBlockedRdoUpdatesForSync()).toBe(0);
    expect(
      (await database.get("outbox_mutations", MUTATION_ID))?.blockedReason,
    ).toBe("RDO_CREATION_CONTEXT_REQUIRED");
  });

  /*
   * Soltar é sobre este bloqueio, não sobre bloqueio nenhum. Uma linha retida
   * por outro motivo continua retida — inclusive a que foi superada, cuja marca
   * é o que liga a original à substituta.
   */
  it("não mexe em linha retida por outro motivo", async () => {
    await semear(edicaoBloqueadaNaFilaAntiga({
      blockedReason: "MAO_OBRA_SEM_VINCULO_EXIGE_DECISAO",
    }));

    expect(await releaseBlockedRdoUpdatesForSync()).toBe(0);

    const database = await getCortexDb();
    expect(
      (await database.get("outbox_mutations", MUTATION_ID))?.blockedReason,
    ).toBe("MAO_OBRA_SEM_VINCULO_EXIGE_DECISAO");
  });

  it("não solta a criação, que continua precisando do recibo", async () => {
    await semear(edicaoBloqueadaNaFilaAntiga({
      operacao: "CRIAR_RDO",
      baseVersao: null,
    }));

    expect(await releaseBlockedRdoUpdatesForSync()).toBe(0);

    const database = await getCortexDb();
    expect(
      (await database.get("outbox_mutations", MUTATION_ID))?.blockedReason,
    ).toBe("RDO_CREATION_CONTEXT_REQUIRED");
  });
});

/*
 * A trava do contrato, medida na porta por onde a edição realmente entra.
 *
 * O guarda estrutural em `localRdoService.test.ts` impede que a regra volte
 * como função exportada; este impede que ela volte como literal no meio do
 * caminho de escrita, que é como ela chegaria de novo sem ninguém notar.
 */
describe("edição de rascunho sem recibo de contexto", () => {
  it("não nasce bloqueada, mesmo com o rascunho sem recibo", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoEditado());

    const { saveExistingRdoDraftAtomically, rdoDraftFromLocalRecord } =
      await import("../db/localRdoService");
    const draft = rdoDraftFromLocalRecord(rdoEditado());
    draft.creationContextVersion = null;

    const resultado = await saveExistingRdoDraftAtomically(draft);

    expect(resultado.mutation.blockedReason).toBeNull();
    expect(resultado.mutation.operacao).toBe("ATUALIZAR_RDO_RASCUNHO");
    expect(
      selectReadyOutboxMutations(
        await (await getCortexDb()).getAll("outbox_mutations"),
        10,
      ).map((item) => item.clientMutationId),
    ).toEqual([resultado.mutation.clientMutationId]);
  });
});
