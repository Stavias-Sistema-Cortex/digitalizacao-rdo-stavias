import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import type {
  LocalRdoRecord,
  OperationalEventRecord,
  OutboxMutationRecord,
} from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import { createMemoryRepository } from "../../features/home/memory/memoryRepository";
import {
  memoryCoverage,
  memoryStatusLabel,
} from "../../features/home/memory/memorySearchDocument";
import { descartarEdicaoEmConflito } from "./syncStorage";

const OBRA_ID = "00000000-0000-4000-8000-000000000a01";
const RDO_ID = "00000000-0000-4000-8000-000000000a02";
const MUTATION_ID = "00000000-0000-4000-8000-000000000a03";
const EVENTO_SALVO_ID = "00000000-0000-4000-8000-000000000a04";
const EVENTO_RELACAO_ID = "00000000-0000-4000-8000-000000000a05";
const OCORRIDO_EM = "2026-08-13T14:14:00.000Z";
const ESCOPO = "escopo-memoria";

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

function rdoLocal(): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-0010",
    dataRdo: "2026-08-13",
    statusRdo: "RASCUNHO",
    syncStatus: "CONFLICT",
    versaoEntidade: 2,
    payload: { observacoes: "chuva forte no trecho" },
    createdAt: OCORRIDO_EM,
    updatedAt: OCORRIDO_EM,
  };
}

/** O evento que o RDO escreve ao salvar: sem veredito e sem clientMutationId. */
function eventoLegado(id: string): OperationalEventRecord {
  return {
    id,
    type: "RDO_SALVO_OFFLINE",
    principalEntity: { tipo: "RDO", id: RDO_ID, nome: "RDO-0010" },
    principalEntityKey: `RDO:${RDO_ID}`,
    relatedEntities: [],
    obraId: OBRA_ID,
    rdoId: RDO_ID,
    colaboradorId: null,
    occurredAt: OCORRIDO_EM,
    syncedAt: null,
    origin: "OFFLINE",
    responsibleUserId: null,
    responsibleUserName: null,
    payload: {},
    syncStatus: "SYNC_FAILED",
    schemaVersion: 1,
  };
}

function mutacaoEmConflito(): OutboxMutationRecord {
  return {
    clientMutationId: MUTATION_ID,
    entidadeTipo: "RDO",
    entidadeId: RDO_ID,
    operacao: "ATUALIZAR_RDO_RASCUNHO",
    baseVersao: 2,
    payload: {
      operationalEvents: [
        { id: EVENTO_SALVO_ID },
        { id: EVENTO_RELACAO_ID },
      ],
    },
    status: "CONFLICT",
    tentativas: 1,
    ultimaTentativaEm: OCORRIDO_EM,
    ultimoErro: "Versão mais recente no servidor.",
    conflito: { versaoAtual: 4 },
    blockedReason: null,
    lastSafeCode: "VERSION_CONFLICT",
    criadaNoClienteEm: OCORRIDO_EM,
    updatedAt: OCORRIDO_EM,
  };
}

async function buscarDocumentos() {
  const database = await getCortexDb();
  const repository = createMemoryRepository(database);
  const result = await repository.search({
    userId,
    scopeHash: ESCOPO,
    filters: {},
    allowedWorksiteIds: null,
    limit: 10,
  });
  return result;
}

beforeEach(async () => {
  userId = crypto.randomUUID();
  setSession(session());
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);
  const database = await getCortexDb();
  await database.put("rdos", rdoLocal());
  await database.put("operational_events", eventoLegado(EVENTO_SALVO_ID));
  await database.put(
    "operational_events",
    { ...eventoLegado(EVENTO_RELACAO_ID), type: "ENTIDADE_RELACIONADA" },
  );
  await database.put("outbox_mutations", mutacaoEmConflito());
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

/**
 * O conflito legado precisa de nome e de saída.
 *
 * <p>O evento legado não grava veredito nem carrega clientMutationId, mas a
 * mutação que o levou nomeia os eventos dela no payload. Sem essa ligação a
 * Memória lia o evento sozinho: a tarja de sincronização dizia "Conflito de
 * versão", a Memória dizia "Falha no envio", nenhuma das duas mostrava o
 * código seguro — e o botão de descarte, que é a única saída de um conflito
 * legado, nunca aparecia. O aparelho ficava preso com um RDO bloqueado para
 * edição e sem exportação, sem nada na tela que destravasse.
 */
describe("conflito legado tem nome e saída", () => {
  it("lê da fila o conflito que o evento não gravou", async () => {
    const { items } = await buscarDocumentos();
    const documento = items.find((item) => item.eventId === EVENTO_SALVO_ID);

    expect(documento?.syncStatus).toBe("CONFLICT");
    expect(memoryStatusLabel(documento!.syncStatus)).toBe("Conflito");
    expect(documento?.errorCategory).toBe("VERSION_CONFLICT");
  });

  it("dá ao cartão a identidade da mutação e a versão remota", async () => {
    const { items } = await buscarDocumentos();
    const review = items.find(
      (item) => item.eventId === EVENTO_SALVO_ID,
    )?.review;

    // status CONFLICT + clientMutationId é a condição do botão
    // "Descartar minha versão" no MemoryLedger.
    expect(review?.status).toBe("CONFLICT");
    expect(review?.clientMutationId).toBe(MUTATION_ID);
    expect(review?.remoteVersion).toBe(4);
  });

  it("descarta a edição legada e liberta os eventos que ela carregava", async () => {
    const descartada = await descartarEdicaoEmConflito(MUTATION_ID);
    expect(descartada).toEqual({
      entidadeTipo: "RDO",
      entidadeId: RDO_ID,
    });

    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", MUTATION_ID)).toBeUndefined();
    // O rastro fica: descartado, não apagado.
    expect(
      await database.get("operational_events", EVENTO_SALVO_ID),
    ).toMatchObject({
      result: "DISCARDED",
      errorCategory: "VERSION_CONFLICT_DISCARDED",
    });
    expect(
      await database.get("operational_events", EVENTO_RELACAO_ID),
    ).toMatchObject({ result: "DISCARDED" });
    // Sem mutação na fila, o RDO volta a acompanhar o servidor — o que
    // desbloqueia a edição e devolve a exportação ao caminho sincronizado.
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      syncStatus: "SYNCED",
    });
  });

  it("após o descarte a tarja para de acusar e o recorte esvazia", async () => {
    await descartarEdicaoEmConflito(MUTATION_ID);

    const { items, localStatuses } = await buscarDocumentos();
    const documento = items.find((item) => item.eventId === EVENTO_SALVO_ID);

    expect(documento?.syncStatus).toBe("DISCARDED");
    expect(documento?.review).toBeNull();
    const cobertura = memoryCoverage({
      online: true,
      metadata: null,
      localStatuses,
    });
    expect(["CONFLICT", "REJECTED", "SYNC_FAILED"]).not.toContain(
      cobertura.code,
    );
  });

  it("lê como fila viva a mutação que voltou a PENDING", async () => {
    const database = await getCortexDb();
    await database.put("outbox_mutations", {
      ...mutacaoEmConflito(),
      status: "PENDING",
      conflito: null,
    });

    const { items } = await buscarDocumentos();
    const documento = items.find((item) => item.eventId === EVENTO_SALVO_ID);

    expect(documento?.syncStatus).toBe("LOCAL_PENDING");
    expect(documento?.review).toBeNull();
  });
});
