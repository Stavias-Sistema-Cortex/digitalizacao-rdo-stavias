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
  OutboxMutationRecord,
} from "./db.types";
import { databaseNameForScope } from "./localDataNamespace";
import {
  canCoalesceLegacyRdoMutation,
  saveExistingRdoDraftAtomically,
} from "./localRdoService";
import { toPushMutationRequest } from "../sync/sync.types";
import {
  applyPushResultAtomically,
  markMutationAsSyncing,
  queueErroredMutationsForRetry,
  repairMissingMaoObraReferencesForSync,
  repairMissingObraReferencesForSync,
} from "../sync/syncStorage";
import { selectReadyOutboxMutations } from "../sync/outboxDependencies";

const OBRA_ID = "00000000-0000-4000-8000-000000000701";
const RDO_ID = "00000000-0000-4000-8000-000000000702";
const MUTATION_A_ID = "00000000-0000-4000-8000-000000000703";
const MUTATION_B_ID = "00000000-0000-4000-8000-000000000704";
const MUTATION_C_ID = "00000000-0000-4000-8000-000000000707";
const LEGACY_OBRA_ID = "00000000-0000-4000-8000-000000000705";
const OCCURRED_AT = "2026-07-23T14:00:00.000Z";

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

function rdoWithPayloadA(): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-0702",
    dataRdo: "2026-07-23",
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: 7,
    payload: {
      observacoes: "payload-A-serialized-for-server",
    },
    createdAt: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
  };
}

function legacyMutationWithPayloadA(): OutboxMutationRecord {
  return {
    clientMutationId: MUTATION_A_ID,
    entidadeTipo: "RDO",
    entidadeId: RDO_ID,
    operacao: "ATUALIZAR_RDO_RASCUNHO",
    baseVersao: 7,
    payload: {
      observacoes: "payload-A-serialized-for-server",
    },
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
  };
}

function draftWithPayloadB() {
  const draft = createEmptyRdo();
  draft.id = RDO_ID;
  draft.obraId = OBRA_ID;
  draft.numeroRdo = "RDO-0702";
  draft.dataRdo = "2026-07-23";
  draft.observacoes = "payload-B-saved-while-A-is-in-flight";
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

describe("legacy RDO save while mutation A is in flight", () => {
  it("keeps edit B pending under a distinct identity when response A arrives", async () => {
    const database = await getCortexDb();
    const mutationA = legacyMutationWithPayloadA();
    await database.put("rdos", rdoWithPayloadA());
    await database.put("outbox_mutations", mutationA);

    const requestA = await toPushMutationRequest(mutationA);
    await markMutationAsSyncing(mutationA);

    const inFlightA = await database.get(
      "outbox_mutations",
      MUTATION_A_ID,
    );
    expect(inFlightA).toMatchObject({
      clientMutationId: MUTATION_A_ID,
      status: "SYNCING",
      payload: {
        observacoes: "payload-A-serialized-for-server",
      },
    });
    expect(
      canCoalesceLegacyRdoMutation(
        inFlightA!,
        "ATUALIZAR_RDO_RASCUNHO",
      ),
    ).toBe(false);

    const savedB = await saveExistingRdoDraftAtomically(
      draftWithPayloadB(),
    );
    expect(savedB.mutation).toMatchObject({
      status: "PENDING",
      baseVersao: 7,
      dependsOnMutationIds: [MUTATION_A_ID],
      payload: {
        observacoes: "payload-B-saved-while-A-is-in-flight",
      },
    });
    expect(savedB.mutation.clientMutationId).not.toBe(MUTATION_A_ID);
    expect(
      await database.get("outbox_mutations", MUTATION_A_ID),
    ).toMatchObject({
      status: "SYNCING",
      payload: {
        observacoes: "payload-A-serialized-for-server",
      },
    });
    expect(
      selectReadyOutboxMutations(
        await database.getAll("outbox_mutations"),
        10,
      ),
    ).toEqual([]);

    await applyPushResultAtomically({
      clientMutationId: requestA.clientMutationId,
      status: "APLICADA",
      resultado: { versaoEntidade: 8 },
    });

    expect(
      await database.get("outbox_mutations", MUTATION_A_ID),
    ).toMatchObject({
      status: "SYNCED",
      payload: {
        observacoes: "payload-A-serialized-for-server",
      },
    });
    expect(
      await database.get(
        "outbox_mutations",
        savedB.mutation.clientMutationId,
      ),
    ).toMatchObject({
      status: "PENDING",
      baseVersao: 8,
      dependsOnMutationIds: [MUTATION_A_ID],
      payload: {
        observacoes: "payload-B-saved-while-A-is-in-flight",
      },
    });
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      syncStatus: "PENDING_SYNC",
      versaoEntidade: 8,
      payload: {
        observacoes: "payload-B-saved-while-A-is-in-flight",
      },
    });
    expect(
      selectReadyOutboxMutations(
        await database.getAll("outbox_mutations"),
        10,
      ),
    ).toEqual([
      expect.objectContaining({
        clientMutationId: savedB.mutation.clientMutationId,
        status: "PENDING",
        baseVersao: 8,
      }),
    ]);
  });

  it("rejects a stale response once the serialized attempt is no longer SYNCING", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoWithPayloadA());
    await database.put(
      "outbox_mutations",
      legacyMutationWithPayloadA(),
    );

    await expect(
      applyPushResultAtomically({
        clientMutationId: MUTATION_A_ID,
        status: "APLICADA",
        resultado: { versaoEntidade: 8 },
      }),
    ).rejects.toThrow(/não está em sincronização/i);

    expect(
      await database.get("outbox_mutations", MUTATION_A_ID),
    ).toMatchObject({
      status: "PENDING",
      payload: {
        observacoes: "payload-A-serialized-for-server",
      },
    });
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      syncStatus: "PENDING_SYNC",
      versaoEntidade: 7,
    });
  });

  it("serializes the coalesced pre-mark edit returned by the atomic SYNCING transition", async () => {
    const database = await getCortexDb();
    const listedA = legacyMutationWithPayloadA();
    await database.put("rdos", rdoWithPayloadA());
    await database.put("outbox_mutations", listedA);

    const coalescedB = await saveExistingRdoDraftAtomically(
      draftWithPayloadB(),
    );
    expect(coalescedB.mutation.clientMutationId).toBe(MUTATION_A_ID);
    expect(coalescedB.mutation.status).toBe("PENDING");

    const lockedB = await markMutationAsSyncing(listedA);
    const request = await toPushMutationRequest(lockedB);

    expect(lockedB).toMatchObject({
      clientMutationId: MUTATION_A_ID,
      status: "SYNCING",
      payload: {
        observacoes: "payload-B-saved-while-A-is-in-flight",
      },
    });
    expect(request).toMatchObject({
      clientMutationId: MUTATION_A_ID,
      payload: {
        observacoes: "payload-B-saved-while-A-is-in-flight",
      },
    });
  });

  it("releases corrected edit B after A is definitely rejected", async () => {
    const database = await getCortexDb();
    const mutationA = legacyMutationWithPayloadA();
    await database.put("rdos", rdoWithPayloadA());
    await database.put("outbox_mutations", mutationA);
    await markMutationAsSyncing(mutationA);
    const savedB = await saveExistingRdoDraftAtomically(
      draftWithPayloadB(),
    );

    await applyPushResultAtomically({
      clientMutationId: MUTATION_A_ID,
      status: "REJEITADA",
      erro: "A alteração A foi rejeitada antes do commit.",
    });

    expect(
      await database.get("outbox_mutations", MUTATION_A_ID),
    ).toMatchObject({
      status: "REJECTED",
      blockedReason: `NON_APPLIED_SUPERSEDED_BY:${savedB.mutation.clientMutationId}`,
    });
    expect(
      await database.get(
        "outbox_mutations",
        savedB.mutation.clientMutationId,
      ),
    ).toMatchObject({
      status: "PENDING",
      baseVersao: 7,
      dependsOnMutationIds: [],
      payload: {
        observacoes: "payload-B-saved-while-A-is-in-flight",
      },
    });
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      syncStatus: "PENDING_SYNC",
      versaoEntidade: 7,
      payload: {
        observacoes: "payload-B-saved-while-A-is-in-flight",
      },
    });
    expect(
      selectReadyOutboxMutations(
        await database.getAll("outbox_mutations"),
        10,
      ),
    ).toEqual([
      expect.objectContaining({
        clientMutationId: savedB.mutation.clientMutationId,
      }),
    ]);
  });

  it("releases corrected edit B after a definitely non-applied legacy error", async () => {
    const database = await getCortexDb();
    const mutationA = legacyMutationWithPayloadA();
    await database.put("rdos", rdoWithPayloadA());
    await database.put("outbox_mutations", mutationA);
    await markMutationAsSyncing(mutationA);
    const savedB = await saveExistingRdoDraftAtomically(
      draftWithPayloadB(),
    );

    await applyPushResultAtomically({
      clientMutationId: MUTATION_A_ID,
      status: "ERRO",
      erro: "A transação de A falhou antes do commit.",
    });

    expect(
      await database.get("outbox_mutations", MUTATION_A_ID),
    ).toMatchObject({
      status: "ERROR",
      blockedReason: `NON_APPLIED_SUPERSEDED_BY:${savedB.mutation.clientMutationId}`,
    });
    expect(
      await database.get(
        "outbox_mutations",
        savedB.mutation.clientMutationId,
      ),
    ).toMatchObject({
      status: "PENDING",
      baseVersao: 7,
      dependsOnMutationIds: [],
    });
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      syncStatus: "PENDING_SYNC",
      versaoEntidade: 7,
      payload: {
        observacoes: "payload-B-saved-while-A-is-in-flight",
      },
    });
    expect(
      selectReadyOutboxMutations(
        await database.getAll("outbox_mutations"),
        10,
      ),
    ).toEqual([
      expect.objectContaining({
        clientMutationId: savedB.mutation.clientMutationId,
      }),
    ]);

    expect(await queueErroredMutationsForRetry()).toBe(0);
    expect(
      await database.get("outbox_mutations", MUTATION_A_ID),
    ).toMatchObject({
      status: "ERROR",
      blockedReason: `NON_APPLIED_SUPERSEDED_BY:${savedB.mutation.clientMutationId}`,
    });
  });

  it("keeps ambiguous parallel dependents blocked after a non-applied response", async () => {
    const database = await getCortexDb();
    const mutationA = legacyMutationWithPayloadA();
    await database.put("rdos", rdoWithPayloadA());
    await database.put("outbox_mutations", mutationA);
    await markMutationAsSyncing(mutationA);
    const savedB = await saveExistingRdoDraftAtomically(
      draftWithPayloadB(),
    );
    await database.put("outbox_mutations", {
      ...savedB.mutation,
      clientMutationId: MUTATION_C_ID,
      payload: {
        ...savedB.mutation.payload,
        observacoes: "parallel-payload-C",
      },
      criadaNoClienteEm: "2026-07-23T14:00:02.000Z",
      updatedAt: "2026-07-23T14:00:02.000Z",
    });

    await applyPushResultAtomically({
      clientMutationId: MUTATION_A_ID,
      status: "REJEITADA",
      erro: "A alteração A não foi aplicada.",
    });

    const rejectedA = await database.get(
      "outbox_mutations",
      MUTATION_A_ID,
    );
    expect(rejectedA).toMatchObject({ status: "REJECTED" });
    expect(rejectedA?.blockedReason ?? "").not.toMatch(
      /^NON_APPLIED_SUPERSEDED_BY:/,
    );
    expect(
      await database.get(
        "outbox_mutations",
        savedB.mutation.clientMutationId,
      ),
    ).toMatchObject({
      dependsOnMutationIds: [MUTATION_A_ID],
    });
    expect(
      await database.get("outbox_mutations", MUTATION_C_ID),
    ).toMatchObject({
      dependsOnMutationIds: [MUTATION_A_ID],
    });
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      syncStatus: "ERROR",
    });
    expect(
      selectReadyOutboxMutations(
        await database.getAll("outbox_mutations"),
        10,
      ),
    ).toEqual([]);
  });

  it("keeps edit B blocked when response A is a conflict", async () => {
    const database = await getCortexDb();
    const mutationA = legacyMutationWithPayloadA();
    await database.put("rdos", rdoWithPayloadA());
    await database.put("outbox_mutations", mutationA);
    await markMutationAsSyncing(mutationA);
    const savedB = await saveExistingRdoDraftAtomically(
      draftWithPayloadB(),
    );

    await applyPushResultAtomically({
      clientMutationId: MUTATION_A_ID,
      status: "CONFLITO",
      erro: "Conflito de versão.",
      conflito: { versaoAtual: 8 },
    });

    expect(
      await database.get(
        "outbox_mutations",
        savedB.mutation.clientMutationId,
      ),
    ).toMatchObject({
      status: "PENDING",
      dependsOnMutationIds: [MUTATION_A_ID],
    });
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      syncStatus: "CONFLICT",
      versaoEntidade: 8,
    });
    expect(
      selectReadyOutboxMutations(
        await database.getAll("outbox_mutations"),
        10,
      ),
    ).toEqual([]);
  });

  it("rewires edit B when ERROR repair replaces A with a new identity", async () => {
    const database = await getCortexDb();
    await database.put("obras", {
      id: OBRA_ID,
      codigoContrato: "CTR-701",
      nome: "Obra 701",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "ATIVA",
      observacoes: null,
      latitude: null,
      longitude: null,
      valorContratual: null,
      updatedAt: OCCURRED_AT,
    });
    await database.put("rdos", {
      ...rdoWithPayloadA(),
      obraId: LEGACY_OBRA_ID,
      syncStatus: "ERROR",
      payload: {
        id: RDO_ID,
        obraId: LEGACY_OBRA_ID,
        contrato: "CTR-701",
        observacoes: "payload-A-with-legacy-worksite",
      },
    });
    await database.put("outbox_mutations", {
      ...legacyMutationWithPayloadA(),
      status: "ERROR",
      ultimoErro: `Obra não encontrada: ${LEGACY_OBRA_ID}`,
      payload: {
        id: RDO_ID,
        obraId: LEGACY_OBRA_ID,
        contrato: "CTR-701",
        observacoes: "payload-A-with-legacy-worksite",
      },
    });
    await database.put("outbox_mutations", {
      ...legacyMutationWithPayloadA(),
      clientMutationId: MUTATION_B_ID,
      payload: {
        id: RDO_ID,
        obraId: LEGACY_OBRA_ID,
        contrato: "CTR-701",
        observacoes: "payload-B-after-A",
      },
      dependsOnMutationIds: [MUTATION_A_ID],
      criadaNoClienteEm: "2026-07-23T14:00:01.000Z",
      updatedAt: "2026-07-23T14:00:01.000Z",
    });

    expect(await repairMissingObraReferencesForSync()).toBe(2);

    const afterRepair = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      RDO_ID,
    );
    expect(
      afterRepair.find(
        (mutation) => mutation.clientMutationId === MUTATION_A_ID,
      ),
    ).toBeUndefined();
    const repairedA = afterRepair.find(
      (mutation) => mutation.clientMutationId !== MUTATION_B_ID,
    )!;
    expect(repairedA).toMatchObject({
      status: "PENDING",
      baseVersao: 7,
      payload: {
        obraId: OBRA_ID,
        contrato: "CTR-701",
      },
    });
    expect(
      afterRepair.find(
        (mutation) => mutation.clientMutationId === MUTATION_B_ID,
      ),
    ).toMatchObject({
      status: "PENDING",
      dependsOnMutationIds: [repairedA.clientMutationId],
      payload: {
        obraId: OBRA_ID,
        observacoes: "payload-B-after-A",
      },
    });
    expect(selectReadyOutboxMutations(afterRepair, 10)).toEqual([
      expect.objectContaining({
        clientMutationId: repairedA.clientMutationId,
      }),
    ]);

    await markMutationAsSyncing(repairedA);
    await applyPushResultAtomically({
      clientMutationId: repairedA.clientMutationId,
      status: "APLICADA",
      resultado: { versaoEntidade: 8 },
    });
    const afterAppliedRepair = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      RDO_ID,
    );
    expect(
      afterAppliedRepair.find(
        (mutation) => mutation.clientMutationId === MUTATION_B_ID,
      ),
    ).toMatchObject({
      status: "PENDING",
      baseVersao: 8,
      dependsOnMutationIds: [repairedA.clientMutationId],
    });
    expect(selectReadyOutboxMutations(afterAppliedRepair, 10)).toEqual([
      expect.objectContaining({
        clientMutationId: MUTATION_B_ID,
        baseVersao: 8,
      }),
    ]);
  });

  it("preserves the rewired dependency when A and B both need mao de obra repair", async () => {
    const database = await getCortexDb();
    const missingWorkerError =
      "DataIntegrityViolationException: rdo_mao_obra colaborador foreign key constraint fk_rdo_mao_obra_colaborador";
    const maoObra = [
      {
        id: "mao-701",
        colaboradorId: "00000000-0000-4000-8000-000000000706",
        nomeColaborador: "Trabalhador legado",
        cargo: "Servente",
      },
    ];
    await database.put("rdos", {
      ...rdoWithPayloadA(),
      syncStatus: "ERROR",
      payload: {
        id: RDO_ID,
        obraId: OBRA_ID,
        observacoes: "payload-B-after-A",
        maoObra,
      },
    });
    await database.put("outbox_mutations", {
      ...legacyMutationWithPayloadA(),
      status: "ERROR",
      ultimoErro: missingWorkerError,
      payload: {
        id: RDO_ID,
        obraId: OBRA_ID,
        observacoes: "payload-A",
        maoObra,
      },
    });
    await database.put("outbox_mutations", {
      ...legacyMutationWithPayloadA(),
      clientMutationId: MUTATION_B_ID,
      status: "PENDING",
      ultimoErro: missingWorkerError,
      dependsOnMutationIds: [MUTATION_A_ID],
      criadaNoClienteEm: "2026-07-23T14:00:01.000Z",
      updatedAt: "2026-07-23T14:00:01.000Z",
      payload: {
        id: RDO_ID,
        obraId: OBRA_ID,
        observacoes: "payload-B-after-A",
        maoObra,
      },
    });

    expect(await repairMissingMaoObraReferencesForSync()).toBe(2);

    const afterRepair = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      RDO_ID,
    );
    const repairedA = afterRepair.find(
      (mutation) => mutation.clientMutationId !== MUTATION_B_ID,
    )!;
    expect(repairedA).toMatchObject({
      status: "PENDING",
      payload: {
        maoObra: [
          expect.objectContaining({
            colaboradorId: null,
            nomeColaborador: "Trabalhador legado",
          }),
        ],
      },
    });
    expect(
      afterRepair.find(
        (mutation) => mutation.clientMutationId === MUTATION_B_ID,
      ),
    ).toMatchObject({
      status: "PENDING",
      dependsOnMutationIds: [repairedA.clientMutationId],
      payload: {
        observacoes: "payload-B-after-A",
        maoObra: [
          expect.objectContaining({
            colaboradorId: null,
            nomeColaborador: "Trabalhador legado",
          }),
        ],
      },
    });
    expect(selectReadyOutboxMutations(afterRepair, 10)).toEqual([
      expect.objectContaining({
        clientMutationId: repairedA.clientMutationId,
      }),
    ]);
  });
});
