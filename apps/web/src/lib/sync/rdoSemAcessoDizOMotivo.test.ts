import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const lookupMocks = vi.hoisted(() => ({
  context: vi.fn(),
}));

vi.mock("../../features/rdos/rdoLookupApi", () => ({
  buscarContextoDeCriacaoRdo: lookupMocks.context,
}));

import { ApiError, ApiTransportError } from "../api/apiError";
import { clearSession, setSession } from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import type { LocalRdoRecord, OutboxMutationRecord } from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import { hydrateBlockedRdoCreationContextsForSync } from "../db/localRdoService";

/**
 * Falta de rede passa; falta de acesso não passa sozinha.
 *
 * <p>Um RDO criado numa obra que a pessoa não alcança fica esperando o recibo
 * de contexto, que nunca vem: o servidor responde 403. O aparelho tratava isso
 * como falha transitória, batia no servidor a cada minuto para sempre e exibia
 * "a sincronização tentará novamente" — a alguém que não tinha o que esperar,
 * porque o que faltava era alguém vinculá-la à obra.
 */

const OBRA_ID = "00000000-0000-4000-8000-000000000501";
const RDO_ID = "00000000-0000-4000-8000-000000000503";
const MUTATION_ID = "00000000-0000-4000-8000-000000000504";
const OCCURRED_AT = "2026-08-10T12:00:00.000Z";

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

function rdoCriadoEmCampo(): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-0021",
    dataRdo: "2026-08-10",
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: null,
    payload: { observacoes: "frente da manhã" },
    createdAt: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
  } as LocalRdoRecord;
}

function criacaoBloqueadaPorContexto(): OutboxMutationRecord {
  return {
    clientMutationId: MUTATION_ID,
    entidadeTipo: "RDO",
    entidadeId: RDO_ID,
    operacao: "CRIAR_RDO",
    baseVersao: null,
    payload: { observacoes: "frente da manhã" },
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    blockedReason: "RDO_CREATION_CONTEXT_REQUIRED",
    criadaNoClienteEm: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
  } as OutboxMutationRecord;
}

async function semear(): Promise<void> {
  const database = await getCortexDb();
  await database.put("rdos", rdoCriadoEmCampo());
  await database.put("outbox_mutations", criacaoBloqueadaPorContexto());
}

async function esperaEmMinutos(): Promise<number> {
  const database = await getCortexDb();
  const mutation = await database.get("outbox_mutations", MUTATION_ID);
  const proxima = Date.parse(String(mutation?.nextAttemptAt));
  return Math.round((proxima - Date.parse(String(mutation?.updatedAt))) / 60_000);
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

describe("RDO parado por contexto de obra", () => {
  it("diz que falta acesso, em vez de prometer nova tentativa", async () => {
    await semear();
    lookupMocks.context.mockRejectedValue(
      new ApiError("proibido", 403, null),
    );

    await hydrateBlockedRdoCreationContextsForSync();

    const database = await getCortexDb();
    const mutation = await database.get("outbox_mutations", MUTATION_ID);
    expect(mutation?.ultimoErro).toMatch(/não tem acesso a esta obra/i);
    expect(mutation?.ultimoErro).toMatch(/Gestão de Obras/);
  });

  /*
   * Acesso é concedido por gente, não por retentativa. Insistir de minuto em
   * minuto só gasta bateria e rede de quem está em campo.
   */
  it("espalha as tentativas quando o motivo é acesso", async () => {
    await semear();
    lookupMocks.context.mockRejectedValue(
      new ApiError("proibido", 403, null),
    );

    await hydrateBlockedRdoCreationContextsForSync();

    expect(await esperaEmMinutos()).toBe(15);
  });

  it("mantém a insistência de um minuto quando o que faltou foi rede", async () => {
    await semear();
    lookupMocks.context.mockRejectedValue(
      new ApiTransportError("sem rede", "CONNECTION"),
    );

    await hydrateBlockedRdoCreationContextsForSync();

    const database = await getCortexDb();
    const mutation = await database.get("outbox_mutations", MUTATION_ID);
    expect(mutation?.ultimoErro).toMatch(/tentará novamente/i);
    expect(await esperaEmMinutos()).toBe(1);
  });

  /*
   * Outros erros do servidor não viram "falta de acesso": 404 e 500 são falha
   * do lado de lá, e prometer que a sincronização insiste é a verdade neles.
   */
  it("não trata qualquer erro de servidor como falta de acesso", async () => {
    await semear();
    lookupMocks.context.mockRejectedValue(
      new ApiError("indisponível", 503, null),
    );

    await hydrateBlockedRdoCreationContextsForSync();

    const database = await getCortexDb();
    const mutation = await database.get("outbox_mutations", MUTATION_ID);
    expect(mutation?.ultimoErro).toMatch(/tentará novamente/i);
  });

  it("não perde o RDO nem o tira da fila em nenhum dos casos", async () => {
    await semear();
    lookupMocks.context.mockRejectedValue(
      new ApiError("proibido", 403, null),
    );

    await hydrateBlockedRdoCreationContextsForSync();

    const database = await getCortexDb();
    expect(await database.get("rdos", RDO_ID)).toBeTruthy();
    const mutation = await database.get("outbox_mutations", MUTATION_ID);
    expect(mutation?.status).toBe("PENDING");
    expect(mutation?.blockedReason).toBe("RDO_CREATION_CONTEXT_REQUIRED");
  });
});
