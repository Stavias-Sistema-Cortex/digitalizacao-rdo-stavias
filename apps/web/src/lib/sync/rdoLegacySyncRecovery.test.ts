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
  OperationalEventRecord,
  OutboxMutationRecord,
} from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import {
  hydrateBlockedRdoCreationContextsForSync,
  repairRdoCreateMutationsForSync,
} from "../db/localRdoService";
import {
  createAndPersistLocalPendingRdoDraft,
} from "../../features/rdos/rdoDraftCreation";
import type {
  RdoLocalPendingCreationContextLookup,
} from "../../features/rdos/rdoLookupApi";
import { selectReadyOutboxMutations } from "./outboxDependencies";
import {
  applyPushResultAtomically,
  markMutationAsSyncing,
} from "./syncStorage";

const OBRA_ID = "00000000-0000-4000-8000-000000000301";
const OTHER_OBRA_ID = "00000000-0000-4000-8000-000000000302";
const RDO_ID = "00000000-0000-4000-8000-000000000303";
const MUTATION_ID = "00000000-0000-4000-8000-000000000304";
const EVENT_ID = "00000000-0000-4000-8000-000000000305";
const LATE_EVENT_ID = "00000000-0000-4000-8000-000000000306";
const PREVIOUS_RDO_ID = "00000000-0000-4000-8000-000000000307";
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

function localRdo(
  payload: Record<string, unknown> = { observacoes: "offline" },
): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-0303",
    dataRdo: "2026-07-22",
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: null,
    payload,
    createdAt: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
  };
}

function legacyCreate(): OutboxMutationRecord {
  return {
    clientMutationId: MUTATION_ID,
    entidadeTipo: "RDO",
    entidadeId: RDO_ID,
    operacao: "CRIAR_RDO",
    baseVersao: null,
    payload: { stale: true },
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
  return {
    status: "COMPLETE",
    total: 0,
    returned: 0,
    complete: true,
  };
}

function context(worksiteId = OBRA_ID) {
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
      serviceCatalog: {
        status: "NOT_CONFIGURED",
        total: 0,
        returned: 0,
        complete: false,
      },
      priceCatalog: {
        status: "NOT_CONFIGURED",
        total: 0,
        returned: 0,
        complete: false,
      },
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
      worksiteId,
      selectedDate: "2026-07-22",
      previousRdoId: PREVIOUS_RDO_ID,
      generatedAt: OCCURRED_AT,
    },
  };
}

function localPendingContext(): RdoLocalPendingCreationContextLookup {
  return {
    obra: {
      id: OBRA_ID,
      codigoContrato: "CTR-301",
      codigoCw: null,
      nome: "Obra autorizada",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "ATIVA",
    },
    data: "2026-07-22",
    previousRdo: null,
    previousWorkforce: [],
    programacoes: [],
    colaboradores: [],
    equipamentos: [],
  };
}

function event(id: string): OperationalEventRecord {
  return {
    id,
    type: "RDO_CRIADO",
    principalEntity: { tipo: "RDO", id: RDO_ID },
    principalEntityKey: `RDO:${RDO_ID}`,
    relatedEntities: [],
    obraId: OBRA_ID,
    rdoId: RDO_ID,
    colaboradorId: null,
    occurredAt: OCCURRED_AT,
    syncedAt: null,
    origin: "OFFLINE",
    responsibleUserId: userId,
    responsibleUserName: "Encarregado",
    payload: { evidence: id },
    syncStatus: "PENDING_SYNC",
    schemaVersion: 1,
  };
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

describe("legacy RDO context hydration", () => {
  it("mantém CREATE canônico bloqueado até obter receipt e só então cria substituto push-ready", async () => {
    const created = await createAndPersistLocalPendingRdoDraft(
      localPendingContext(),
      {
        draftId: RDO_ID,
        occurredAt: OCCURRED_AT,
      },
    );
    expect(selectReadyOutboxMutations([created.mutation], 10)).toEqual([]);
    lookupMocks.context.mockResolvedValue({
      ...context(),
      previousRdo: null,
      provenance: {
        ...context().provenance,
        previousRdoId: null,
      },
    });

    expect(await hydrateBlockedRdoCreationContextsForSync()).toBe(1);

    const database = await getCortexDb();
    const mutations = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      RDO_ID,
    );
    expect(mutations).toHaveLength(2);
    expect(
      mutations.find(
        (mutation) =>
          mutation.clientMutationId === created.mutation.clientMutationId,
      ),
    ).toMatchObject({
      status: "REJECTED",
      blockedReason: expect.stringMatching(/^SUPERSEDED_BY:/),
    });
    const replacement = mutations.find(
      (mutation) =>
        mutation.clientMutationId !== created.mutation.clientMutationId,
    );
    expect(replacement).toMatchObject({
      schemaVersion: 13,
      status: "PENDING",
      blockedReason: null,
      causationId: created.mutation.clientMutationId,
      payload: {
        obraId: OBRA_ID,
        dataRdo: "2026-07-22",
        creationContextVersion: 50,
      },
    });
    expect(selectReadyOutboxMutations(mutations, 10)).toEqual([replacement]);
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      syncStatus: "PENDING_SYNC",
      payload: {
        creationContextVersion: 50,
        creationContextCache: {
          worksiteId: OBRA_ID,
          selectedDate: "2026-07-22",
          receiptVersion: 50,
        },
      },
    });
  });

  it("persists a scoped durable receipt before rebuilding and unblocking the create", async () => {
    const database = await getCortexDb();
    await database.put("rdos", localRdo());
    await database.put("outbox_mutations", legacyCreate());
    lookupMocks.context.mockResolvedValue(context());

    expect(await hydrateBlockedRdoCreationContextsForSync()).toBe(1);
    expect(await repairRdoCreateMutationsForSync()).toBe(1);

    expect(lookupMocks.context).toHaveBeenCalledWith(OBRA_ID, "2026-07-22");
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      payload: {
        previousRdoId: PREVIOUS_RDO_ID,
        creationContextVersion: 50,
        creationContextCache: {
          schemaVersion: 1,
          worksiteId: OBRA_ID,
          selectedDate: "2026-07-22",
          receiptVersion: 50,
        },
      },
    });
    expect(await database.get("outbox_mutations", MUTATION_ID)).toMatchObject({
      status: "PENDING",
      blockedReason: null,
      payload: {
        previousRdoId: PREVIOUS_RDO_ID,
        creationContextVersion: 50,
      },
    });
  });

  it("keeps scope-mismatched or unavailable context retryable and blocked", async () => {
    const database = await getCortexDb();
    await database.put("rdos", localRdo());
    await database.put("outbox_mutations", legacyCreate());
    lookupMocks.context.mockResolvedValue(context(OTHER_OBRA_ID));

    expect(await hydrateBlockedRdoCreationContextsForSync()).toBe(0);
    expect(await database.get("outbox_mutations", MUTATION_ID)).toMatchObject({
      status: "PENDING",
      blockedReason: "RDO_CREATION_CONTEXT_REQUIRED",
      ultimoErro: expect.stringMatching(/tentará novamente/),
      nextAttemptAt: expect.any(String),
    });
    expect(await database.get("rdos", RDO_ID)).not.toMatchObject({
      payload: { creationContextVersion: expect.any(Number) },
    });

    lookupMocks.context.mockRejectedValue(new TypeError("offline"));
    expect(await hydrateBlockedRdoCreationContextsForSync()).toBe(0);
    expect(await database.get("outbox_mutations", MUTATION_ID)).toMatchObject({
      status: "PENDING",
      blockedReason: "RDO_CREATION_CONTEXT_REQUIRED",
    });
  });
});

describe("authoritative RDO number reconciliation", () => {
  it("persists the server-assigned number in the local record and payload", async () => {
    const database = await getCortexDb();
    const mutation = {
      ...legacyCreate(),
      blockedReason: null,
    };
    await database.put(
      "rdos",
      localRdo({
        numeroRdo: "RDO-0303",
        observacoes: "offline",
      }),
    );
    await database.put("outbox_mutations", mutation);
    await markMutationAsSyncing(mutation);

    await applyPushResultAtomically({
      clientMutationId: MUTATION_ID,
      status: "APLICADA",
      resultado: {
        id: RDO_ID,
        numeroRdo: "RDO-0310",
        versaoEntidade: 1,
      },
    });

    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      numeroRdo: "RDO-0310",
      syncStatus: "SYNCED",
      versaoEntidade: 1,
      payload: {
        numeroRdo: "RDO-0310",
        observacoes: "offline",
      },
    });
  });
});

describe("legacy RDO event evidence repair", () => {
  it("substitui uma identidade já tentada sem reescrever o conteúdo registrado no servidor", async () => {
    const database = await getCortexDb();
    await database.put(
      "rdos",
      localRdo({ creationContextVersion: 48, observacoes: "corrigido" }),
    );
    await database.put("outbox_mutations", {
      ...legacyCreate(),
      status: "ERROR",
      tentativas: 1,
      ultimaTentativaEm: "2026-07-27T14:28:20.000Z",
      ultimoErro: "clientMutationId já foi usado com outro conteúdo.",
      blockedReason: null,
      payload: { creationContextVersion: 48, observacoes: "antigo" },
    });

    expect(await repairRdoCreateMutationsForSync()).toBe(1);

    const mutations = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      RDO_ID,
    );
    expect(mutations).toHaveLength(2);
    expect(
      mutations.find(
        (mutation) => mutation.clientMutationId === MUTATION_ID,
      ),
    ).toMatchObject({
      status: "REJECTED",
      payload: { observacoes: "antigo" },
      blockedReason: expect.stringMatching(/^SUPERSEDED_BY:/),
    });
    const replacement = mutations.find(
      (mutation) => mutation.clientMutationId !== MUTATION_ID,
    );
    expect(replacement).toMatchObject({
      schemaVersion: 13,
      status: "PENDING",
      causationId: MUTATION_ID,
      payload: {
        creationContextVersion: 48,
        observacoes: "corrigido",
      },
    });
    expect(selectReadyOutboxMutations(mutations, 10)).toEqual([replacement]);
  });

  it("survives reopen and marks only events carried by the applied mutation", async () => {
    let database = await getCortexDb();
    await database.put("rdos", localRdo({ creationContextVersion: 48 }));
    await database.put("outbox_mutations", legacyCreate());
    await database.put("operational_events", event(EVENT_ID));

    expect(await repairRdoCreateMutationsForSync()).toBe(1);
    await closeCortexDb();
    database = await getCortexDb();

    const repaired = await database.get("outbox_mutations", MUTATION_ID);
    expect(repaired?.payload.operationalEvents).toEqual([
      expect.objectContaining({
        id: EVENT_ID,
        payload: { evidence: EVENT_ID },
        syncStatus: "PENDING_SYNC",
      }),
    ]);

    await database.put("operational_events", event(LATE_EVENT_ID));
    await markMutationAsSyncing(repaired!);
    await applyPushResultAtomically({
      clientMutationId: MUTATION_ID,
      status: "APLICADA",
      resultado: { versaoEntidade: 1 },
    });

    expect(await database.get("operational_events", EVENT_ID)).toMatchObject({
      syncStatus: "SYNCED",
      syncedAt: expect.any(String),
    });
    expect(await database.get("operational_events", LATE_EVENT_ID)).toMatchObject({
      syncStatus: "PENDING_SYNC",
      syncedAt: null,
    });
  });
});
