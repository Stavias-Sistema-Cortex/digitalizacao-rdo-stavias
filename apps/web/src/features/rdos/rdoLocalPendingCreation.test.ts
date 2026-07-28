import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  clearSession,
  setSession,
} from "../auth/authSession";
import {
  closeCortexDb,
  getCortexDb,
  initializeCortexDb,
} from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import { updateSyncState } from "../../lib/db/syncStateRepository";
import { selectReadyOutboxMutations } from "../../lib/sync/outboxDependencies";
import { createEmptyRdo } from "./createEmptyRdo";
import {
  putRdoCreationContext,
  requireRdoDraftCreationContext,
} from "./rdoCreationContextRepository";
import {
  createAndPersistLocalPendingRdoDraft,
} from "./rdoDraftCreation";
import type {
  RdoCreationContextLookup,
  RdoLocalPendingCreationContextLookup,
} from "./rdoLookupApi";

const USER_ID = "00000000-0000-4000-8000-000000000201";
const DEVICE_ID = "00000000-0000-4000-8000-000000000202";
const OBRA_ID = "00000000-0000-4000-8000-000000000203";
const RDO_ID = "00000000-0000-4000-8000-000000000204";
const PREVIOUS_RDO_ID = "00000000-0000-4000-8000-000000000205";
const WORKFORCE_ITEM_ID = "00000000-0000-4000-8000-000000000206";
const WORKER_ID = "00000000-0000-4000-8000-000000000207";
const databaseNames = new Set<string>();

function session(obraIds = [OBRA_ID]) {
  return {
    colaboradorId: USER_ID,
    nome: "Encarregado",
    papelAcesso: "BETA" as const,
    escopoGlobal: false,
    obraIds,
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  };
}

function localPendingContext(): RdoLocalPendingCreationContextLookup {
  return {
    obra: {
      id: OBRA_ID,
      codigoContrato: "CTR-203",
      codigoCw: "CW203",
      nome: "Obra real",
      cliente: "Cliente real",
      cidade: "Iracemápolis",
      uf: "SP",
      rodovia: "SP-151",
      status: "ATIVA",
    },
    data: "2026-07-22",
    previousRdo: {
      id: PREVIOUS_RDO_ID,
      numeroRdo: "RDO-0204",
      dataRdo: "2026-07-21",
      status: "ENVIADO",
    },
    previousWorkforce: [
      {
        sourceItemId: WORKFORCE_ITEM_ID,
        sourceRdoId: PREVIOUS_RDO_ID,
        collaboratorId: WORKER_ID,
        nameSnapshot: "Ana Operadora",
        roleSnapshot: "Operadora",
        linkType: "PRÓPRIO",
        quantity: 1,
        startTime: "07:00:00",
        endTime: "17:00:00",
        observations: null,
        availability: "AVAILABLE",
      },
    ],
    programacoes: [],
    colaboradores: [
      {
        id: WORKER_ID,
        codigoColaborador: null,
        nome: "Ana Operadora",
        papelNaObra: null,
        nomePerfil: "Operadora",
      },
    ],
    equipamentos: [],
  };
}

function canonicalContextForNextDay(): RdoCreationContextLookup {
  const complete = {
    status: "COMPLETE" as const,
    total: 0,
    returned: 0,
    complete: true,
  };
  return {
    ...localPendingContext(),
    data: "2026-07-23",
    nextNumberSuggestion: "RDO-0206",
    previousRdo: null,
    previousWorkforce: [],
    colaboradores: [],
    serviceCatalog: [],
    coverage: {
      previousWorkforce: complete,
      programacoes: complete,
      colaboradores: complete,
      equipamentos: complete,
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
      sourceVersion: 1,
      generatedAt: "2026-07-23T12:00:00.000Z",
      staleAfter: "2026-07-23T12:15:00.000Z",
    },
    provenance: {
      receiptVersion: 1,
      sourceVersion: 1,
      worksiteId: OBRA_ID,
      selectedDate: "2026-07-23",
      previousRdoId: null,
      generatedAt: "2026-07-23T12:00:00.000Z",
    },
  };
}

beforeEach(async () => {
  setSession(session());
  const databaseName = await databaseNameForScope(
    USER_ID,
    `BETA:${OBRA_ID}`,
  );
  databaseNames.add(databaseName);
  await initializeCortexDb();
  await updateSyncState({ deviceId: DEVICE_ID, usuarioId: USER_ID });
});

afterEach(async () => {
  await closeCortexDb();
  clearSession();
  for (const databaseName of databaseNames) {
    await deleteDB(databaseName);
  }
  databaseNames.clear();
});

describe("criação local pendente sem receipt canônico", () => {
  it("persiste a identidade obra-data e a equipe anterior real em envelope canônico bloqueado", async () => {
    const created = await createAndPersistLocalPendingRdoDraft(
      localPendingContext(),
      {
        draftId: RDO_ID,
        occurredAt: "2026-07-22T12:00:00.000Z",
        workforceIdFactory: () =>
          "00000000-0000-4000-8000-000000000208",
      },
    );

    expect(created.draft).toMatchObject({
      id: RDO_ID,
      obraId: OBRA_ID,
      dataRdo: "2026-07-22",
      previousRdoId: PREVIOUS_RDO_ID,
      creationContextVersion: null,
      syncStatus: "LOCAL_PENDING",
      contrato: "CTR-203",
      cidade: "Iracemápolis",
      maoObra: [
        {
          origemItemId: WORKFORCE_ITEM_ID,
          sourceRdoId: PREVIOUS_RDO_ID,
          colaboradorId: WORKER_ID,
          nomeColaborador: "Ana Operadora",
          selected: true,
        },
      ],
    });
    expect(created.mutation).toMatchObject({
      schemaVersion: 13,
      entityType: "RDO",
      entityId: RDO_ID,
      operation: "CREATE",
      operacao: "CRIAR_RDO",
      status: "PENDING",
      blockedReason: "RDO_CREATION_CONTEXT_REQUIRED",
      payload: {
        obraId: OBRA_ID,
        dataRdo: "2026-07-22",
        creationContextVersion: null,
      },
    });
    expect(created.mutation.payload).not.toHaveProperty("provenance");
    expect(selectReadyOutboxMutations([created.mutation], 100)).toEqual([]);

    const database = await getCortexDb();
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      obraId: OBRA_ID,
      dataRdo: "2026-07-22",
      syncStatus: "LOCAL_PENDING",
      payload: {
        creationContextVersion: null,
        previousRdoId: PREVIOUS_RDO_ID,
      },
    });
    expect(
      await database.getAllFromIndex(
        "operational_events",
        "by-client-mutation-id",
        created.mutation.clientMutationId,
      ),
    ).toEqual([
      expect.objectContaining({
        schemaVersion: 13,
        type: "RDO_CRIADO",
        obraId: OBRA_ID,
        rdoId: RDO_ID,
      }),
    ]);
  });

  it("não grava nada quando a obra não pertence mais à sessão", async () => {
    await closeCortexDb();
    setSession(session([]));
    const databaseName = await databaseNameForScope(USER_ID, "BETA:");
    databaseNames.add(databaseName);

    await expect(
      createAndPersistLocalPendingRdoDraft(localPendingContext(), {
        draftId: RDO_ID,
      }),
    ).rejects.toThrow("A obra da mutação não pertence ao escopo da sessão.");

    const database = await getCortexDb();
    expect(await database.getAll("rdos")).toEqual([]);
    expect(await database.getAll("outbox_mutations")).toEqual([]);
  });

  it("encadeia dois RDOs offline, herda a mão de obra manual e ordena a sincronização", async () => {
    const firstDraft = createEmptyRdo();
    firstDraft.maoObra = [
      {
        localId: "00000000-0000-4000-8000-000000000211",
        origemItemId: "",
        sourceRdoId: "",
        selected: true,
        colaboradorId: "",
        nomeColaborador: "Maria Servente",
        cargo: "Servente",
        tipoVinculo: "CONTRATADO",
        quantidade: 1,
        horaInicio: "07:00",
        horaFim: "17:00",
        observacoes: "",
      },
    ];
    const firstContext = {
      ...localPendingContext(),
      data: "2026-07-22",
      previousRdo: null,
      previousWorkforce: [],
      colaboradores: [],
    };
    const first = await createAndPersistLocalPendingRdoDraft(firstContext, {
      draftId: RDO_ID,
      baseDraft: firstDraft,
      occurredAt: "2026-07-22T18:00:00.000Z",
    });

    await putRdoCreationContext(
      canonicalContextForNextDay(),
      "2026-07-23T06:00:00.000Z",
    );
    const resolved = await requireRdoDraftCreationContext(
      OBRA_ID,
      "2026-07-23",
      false,
    );
    expect(resolved).toMatchObject({
      kind: "LOCAL_PENDING",
      source: "LOCAL_CHAIN",
      context: {
        previousRdo: { id: RDO_ID },
        previousWorkforce: [
          {
            sourceItemId:
              "00000000-0000-4000-8000-000000000211",
            collaboratorId: null,
            nameSnapshot: "Maria Servente",
            availability: "AVAILABLE",
          },
        ],
      },
    });
    if (resolved.kind !== "LOCAL_PENDING") {
      throw new Error("O encadeamento offline não foi aplicado.");
    }

    const second = await createAndPersistLocalPendingRdoDraft(
      resolved.context,
      {
        draftId: "00000000-0000-4000-8000-000000000212",
        occurredAt: "2026-07-23T18:00:00.000Z",
        workforceIdFactory: () =>
          "00000000-0000-4000-8000-000000000213",
      },
    );

    expect(second.draft).toMatchObject({
      previousRdoId: RDO_ID,
      maoObra: [
        {
          localId: "00000000-0000-4000-8000-000000000213",
          origemItemId:
            "00000000-0000-4000-8000-000000000211",
          sourceRdoId: RDO_ID,
          colaboradorId: "",
          nomeColaborador: "Maria Servente",
          selected: true,
        },
      ],
    });
    expect(second.mutation.dependsOnMutationIds).toEqual([
      first.mutation.clientMutationId,
    ]);
    expect(selectReadyOutboxMutations(
      [second.mutation, first.mutation],
      Date.now(),
    )).toEqual([]);
  });
});
