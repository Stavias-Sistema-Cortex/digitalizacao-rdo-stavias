import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import { databaseNameForScope } from "../db/localDataNamespace";
import { updateSyncState } from "../db/syncStateRepository";
import { reconcileCanonicalConflict } from "./syncStorage";

const COLABORADOR_ID = "00000000-0000-4000-8000-0000000006c1";
const OBRA_ID = "00000000-0000-4000-8000-0000000006c2";
const EQUIPE_ID = "00000000-0000-4000-8000-0000000006c3";
const MUTATION_ID = "00000000-0000-4000-8000-0000000006c4";
const DEVICE_ID = "00000000-0000-4000-8000-0000000006c5";
const EVENTO_ID = "00000000-0000-4000-8000-0000000006c6";

let databaseName = "";

async function semearConflitoDeEquipe() {
  const database = await getCortexDb();
  await database.put("outbox_mutations", {
    clientMutationId: MUTATION_ID,
    schemaVersion: 13,
    entityType: "EQUIPE",
    entidadeTipo: "EQUIPE",
    entityId: EQUIPE_ID,
    entidadeId: EQUIPE_ID,
    obraId: OBRA_ID,
    operation: "TRANSITION",
    operacao: "ARQUIVAR_EQUIPE",
    baseVersion: 6,
    baseVersao: 6,
    status: "CONFLICT",
    lastSafeCode: "VERSION_CONFLICT",
    ultimoErro: "Conflito de versão da equipe.",
    // O conflito precisa ser auto-mesclável, senão a reconciliação desiste
    // antes de chegar à loja e o teste não provaria nada. Aqui o servidor está
    // na versão 7 e mexeu só na descrição; a edição local mexeu só no status.
    conflito: {
      versaoAtual: 7,
      remoteCompleto: true,
      snapshotRemoto: {
        id: EQUIPE_ID,
        obraId: OBRA_ID,
        obraPrincipalId: OBRA_ID,
        nome: "FR Conservação",
        descricao: "Descrição vinda do servidor.",
        status: "ATIVA",
        inicioValidadeEm: "2026-08-01",
        fimValidadeEm: null,
      },
    },
    tentativas: 1,
    retryAttempt: 0,
    nextAttemptAt: null,
    blockedReason: null,
    occurredAt: "2026-08-05T12:00:00.000Z",
    criadaNoClienteEm: "2026-08-05T12:00:00.000Z",
    createdAt: "2026-08-05T12:00:00.000Z",
    updatedAt: "2026-08-05T12:00:00.000Z",
    payload: {},
    userId: COLABORADOR_ID,
    deviceId: DEVICE_ID,
    correlationId: MUTATION_ID,
    causationId: null,
    transport: "SYNC_PUSH",
    dependsOnMutationIds: [],
    relatedEntities: [],
    fieldPatch: { changedFields: ["status", "fimValidadeEm"] },
    trace: {
      actorId: COLABORADOR_ID,
      deviceId: DEVICE_ID,
      authorizationScope: [OBRA_ID],
      correlationId: MUTATION_ID,
      causationId: null,
      ontologyEventId: EVENTO_ID,
      payloadHash: "0".repeat(64),
    },
  } as never);

  // O evento local único é pré-requisito da reconciliação. Sem ele o teste
  // pararia antes do ponto que interessa e não provaria nada sobre a loja.
  await database.put("operational_events", {
    id: EVENTO_ID,
    type: "EQUIPE_TRANSICIONADA",
    principalEntity: { type: "EQUIPE", id: EQUIPE_ID },
    principalEntityKey: `EQUIPE:${EQUIPE_ID}`,
    relatedEntities: [],
    obraId: OBRA_ID,
    rdoId: null,
    colaboradorId: COLABORADOR_ID,
    occurredAt: "2026-08-05T12:00:00.000Z",
    syncedAt: null,
    origin: "LOCAL",
    responsibleUserId: COLABORADOR_ID,
    responsibleUserName: "Alfa",
    payload: {},
    syncStatus: "LOCAL_ONLY",
    schemaVersion: 13,
    clientMutationId: MUTATION_ID,
    deviceId: DEVICE_ID,
    correlationId: MUTATION_ID,
    causationId: null,
    previousState: {
      id: EQUIPE_ID,
      obraId: OBRA_ID,
      obraPrincipalId: OBRA_ID,
      nome: "FR Conservação",
      descricao: "",
      status: "ATIVA",
      inicioValidadeEm: "2026-08-01",
      fimValidadeEm: null,
    },
    newState: {
      id: EQUIPE_ID,
      obraId: OBRA_ID,
      obraPrincipalId: OBRA_ID,
      nome: "FR Conservação",
      descricao: "",
      status: "ARQUIVADA",
      inicioValidadeEm: "2026-08-01",
      fimValidadeEm: "2026-08-05",
    },
    result: "PENDING",
    errorCategory: null,
    entityVersion: 6,
    serverCommitSequence: null,
  } as never);
}

describe("conflito de entidade sem loja principal", () => {
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
  });

  afterEach(async () => {
    await closeCortexDb();
    if (databaseName) await deleteDB(databaseName);
    clearSession();
  });

  /*
   * O defeito: a reconciliação precisa saber em que loja mora o snapshot para
   * poder reescrevê-lo, e EQUIPE não tem loja registrada. A ausência virava
   * exceção, e quem chama a reconciliação trata exceção como mutação
   * corrompida — carimbando LOCAL_RESULT_APPLY_INVALID. Cada conflito de
   * equipe produzia então dois cartões: o conflito real e uma recusa local que
   * só descrevia a limitação do reconciliador. Foram centenas.
   */
  it("desiste em silêncio em vez de estourar", async () => {
    await semearConflitoDeEquipe();

    await expect(reconcileCanonicalConflict(MUTATION_ID)).resolves.toBeNull();
  });

  /*
   * Desistir tem que deixar o conflito onde estava. Se a desistência mexesse no
   * status, a tela perderia o item de revisão — e o descarte, que é a saída,
   * não teria em que pegar.
   */
  it("deixa o conflito intacto para a revisão explícita", async () => {
    await semearConflitoDeEquipe();

    await reconcileCanonicalConflict(MUTATION_ID);

    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", MUTATION_ID)).toMatchObject({
      status: "CONFLICT",
      lastSafeCode: "VERSION_CONFLICT",
    });
  });
});
