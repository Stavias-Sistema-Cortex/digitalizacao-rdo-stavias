import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import {
  closeCortexDb,
  getCortexDb,
} from "../db/cortexDb";
import {
  applyPushResultAtomically,
  mutationAfterMaoObraReferenceRepair,
  mutationAfterObraReferenceRepair,
  mutationAfterErroredRetry,
  mutationAfterResolvableConflict,
  rdoAfterMaoObraReferenceRepair,
  rdoAfterObraReferenceRepair,
  rdoAfterConflict,
} from "./syncStorage";
import type {
  CanonicalOperationalEventRecord,
  CanonicalOutboxMutationRecord,
  LocalRdoRecord,
  ObraLocalRecord,
  OutboxMutationRecord,
} from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import { selectReadyOutboxMutations } from "./outboxDependencies";
import type { SyncPushMutationResult } from "./sync.types";

const baseRdo = {
  id: "rdo-1",
  syncStatus: "SYNCING",
  versaoEntidade: 3,
  updatedAt: "2026-07-02T10:00:00.000Z",
} as unknown as LocalRdoRecord;

function conflictResult(
  conflito: Record<string, unknown> | null,
): SyncPushMutationResult {
  return {
    clientMutationId: "m-1",
    status: "DESCARTADA",
    entidadeTipo: "RDO",
    entidadeId: "rdo-1",
    conflito,
    erro: "Conflito de versão.",
  };
}

describe("rdoAfterConflict", () => {
  it("adota a versão atual do servidor informada no conflito", () => {
    const updated = rdoAfterConflict(
      baseRdo,
      conflictResult({
        entidadeTipo: "RDO",
        entidadeId: "rdo-1",
        baseVersao: 3,
        versaoAtual: 12,
      }),
      "2026-07-02T11:00:00.000Z",
    );

    expect(updated.syncStatus).toBe("CONFLICT");
    expect(updated.versaoEntidade).toBe(12);
    expect(updated.updatedAt).toBe("2026-07-02T11:00:00.000Z");
  });

  it("mantém a versão local quando o conflito não traz versão válida", () => {
    expect(
      rdoAfterConflict(baseRdo, conflictResult(null), "t").versaoEntidade,
    ).toBe(3);

    expect(
      rdoAfterConflict(
        baseRdo,
        conflictResult({ versaoAtual: "não numérico" }),
        "t",
      ).versaoEntidade,
    ).toBe(3);
  });
});

const baseMutation = {
  clientMutationId: "m-1",
  entidadeTipo: "RDO",
  entidadeId: "rdo-1",
  operacao: "ATUALIZAR_RDO_RASCUNHO",
  baseVersao: 3,
  payload: { numeroRdo: "RDO-1" },
  status: "CONFLICT",
  tentativas: 2,
  ultimaTentativaEm: "2026-07-02T10:30:00.000Z",
  ultimoErro: "Conflito de versão.",
  conflito: {
    entidadeTipo: "RDO",
    entidadeId: "rdo-1",
    baseVersao: 3,
    versaoAtual: 12,
  },
  criadaNoClienteEm: "2026-07-02T10:00:00.000Z",
  updatedAt: "2026-07-02T11:00:00.000Z",
} as OutboxMutationRecord;

describe("mutationAfterResolvableConflict", () => {
  it("não reenvia automaticamente uma substituição integral de RDO", () => {
    expect(
      mutationAfterResolvableConflict(
      baseMutation,
      "2026-07-02T12:00:00.000Z",
      "m-1-retry",
      ),
    ).toBeNull();
  });

  it("não transforma conflito de RDO ausente em uma atualização pendente", () => {
    expect(
      mutationAfterResolvableConflict(
      {
        ...baseMutation,
        conflito: {
          entidadeTipo: "RDO",
          entidadeId: "rdo-1",
          baseVersao: 3,
          versaoAtual: 0,
        },
      },
      "2026-07-02T12:00:00.000Z",
      "m-1-retry",
      ),
    ).toBeNull();
  });

  it("mantém bloqueado quando o conflito não informa versão atual válida", () => {
    expect(
      mutationAfterResolvableConflict(
        {
          ...baseMutation,
          conflito: null,
        },
        "2026-07-02T12:00:00.000Z",
      ),
    ).toBeNull();

    expect(
      mutationAfterResolvableConflict(
        {
          ...baseMutation,
          conflito: {
            versaoAtual: "12",
          },
        },
        "2026-07-02T12:00:00.000Z",
      ),
    ).toBeNull();
  });
});

describe("mutationAfterErroredRetry", () => {
  it("recria o RDO quando a atualização falhou porque o servidor não tem a entidade", () => {
    const updated = mutationAfterErroredRetry(
      {
        ...baseMutation,
        status: "ERROR",
        baseVersao: 0,
        ultimoErro: "RDO não encontrado.",
        conflito: null,
      },
      "2026-07-02T12:00:00.000Z",
    );

    expect(updated.operacao).toBe("CRIAR_RDO");
    expect(updated.baseVersao).toBeNull();
    expect(updated.status).toBe("PENDING");
    expect(updated.tentativas).toBe(0);
    expect(updated.ultimaTentativaEm).toBeNull();
    expect(updated.conflito).toBeNull();
  });
});

const currentInterviasObra: ObraLocalRecord = {
  id: "d5e20a82-8bd4-4031-9616-2fa64230d782",
  codigoContrato: "CW47272",
  nome: "Intervias 2%",
  cliente: "Intervias",
  cidade: "Iracemapolis",
  uf: "SP",
  rodovia: "SP 147",
  status: "ATIVA",
  observacoes: null,
  latitude: null,
  longitude: null,
  valorContratual: null,
  updatedAt: "2026-07-08T12:00:00.000Z",
};

describe("mutationAfterObraReferenceRepair", () => {
  it("reidentifica obra legada por correspondencia unica com a obra local atual", () => {
    const updated = mutationAfterObraReferenceRepair(
      {
        ...baseMutation,
        status: "ERROR",
        baseVersao: 0,
        ultimoErro:
          "Obra não encontrada: 21500000-0000-4000-8000-000000000215",
        conflito: null,
        payload: {
          id: "rdo-1",
          obraId: "21500000-0000-4000-8000-000000000215",
          programacaoId:
            "894c4298-e5b0-423b-aa33-37d6024891bc",
          cliente: "Intervias",
          contrato: "INTERVIAS-2-PCT",
          attachments: [
            {
              id: "foto-1",
              obraId: "21500000-0000-4000-8000-000000000215",
            },
          ],
        },
      },
      [currentInterviasObra],
      "2026-07-08T15:30:00.000Z",
      "m-1-obra-repair",
    );

    expect(updated).not.toBeNull();
    expect(updated?.clientMutationId).toBe("m-1-obra-repair");
    expect(updated?.criadaNoClienteEm).toBe(
      "2026-07-08T15:30:00.000Z",
    );
    expect(updated?.operacao).toBe("CRIAR_RDO");
    expect(updated?.baseVersao).toBeNull();
    expect(updated?.status).toBe("PENDING");
    expect(updated?.payload).toMatchObject({
      obraId: "d5e20a82-8bd4-4031-9616-2fa64230d782",
      programacaoId: null,
      contrato: "CW47272",
      attachments: [
        {
          id: "foto-1",
          obraId: "d5e20a82-8bd4-4031-9616-2fa64230d782",
        },
      ],
    });
    expect(updated?.ultimoErro).toContain(
      "INTERVIAS-2-PCT -> CW47272",
    );
  });

  it("mantem a mutacao bloqueada quando a correspondencia de obra e ambigua", () => {
    const updated = mutationAfterObraReferenceRepair(
      {
        ...baseMutation,
        status: "ERROR",
        ultimoErro:
          "Obra não encontrada: 21500000-0000-4000-8000-000000000215",
        payload: {
          obraId: "21500000-0000-4000-8000-000000000215",
          contrato: "INTERVIAS-2-PCT",
          cliente: "Intervias",
        },
      },
      [
        currentInterviasObra,
        {
          ...currentInterviasObra,
          id: "outra-obra",
        },
      ],
      "2026-07-08T15:30:00.000Z",
    );

    expect(updated).toBeNull();
  });
});

describe("rdoAfterObraReferenceRepair", () => {
  it("mantem o RDO local alinhado com a obra reidentificada", () => {
    const updated = rdoAfterObraReferenceRepair(
      {
        ...baseRdo,
        obraId: "21500000-0000-4000-8000-000000000215",
        programacaoId:
          "894c4298-e5b0-423b-aa33-37d6024891bc",
        payload: {
          obraId: "21500000-0000-4000-8000-000000000215",
          programacaoId:
            "894c4298-e5b0-423b-aa33-37d6024891bc",
          contrato: "INTERVIAS-2-PCT",
        },
      },
      currentInterviasObra,
      "2026-07-08T15:30:00.000Z",
    );

    expect(updated.obraId).toBe(currentInterviasObra.id);
    expect(updated.programacaoId).toBeNull();
    expect(updated.payload).toMatchObject({
      obraId: currentInterviasObra.id,
      programacaoId: null,
      contrato: "CW47272",
    });
    expect(updated.syncStatus).toBe("PENDING_SYNC");
  });
});

describe("mutationAfterMaoObraReferenceRepair", () => {
  it("remove FK legado de mao de obra mantendo o nome informado no RDO", () => {
    const updated = mutationAfterMaoObraReferenceRepair(
      {
        ...baseMutation,
        status: "ERROR",
        operacao: "CRIAR_RDO",
        baseVersao: null,
        ultimoErro:
          "DataIntegrityViolationException: Cannot add or update a child row: a foreign key constraint fails (`cortex_dev`.`rdo_mao_obra`, CONSTRAINT `fk_rdo_mao_obra_colaborador` FOREIGN KEY (`colaborador_id`) REFERENCES `colaborador` (`id`))",
        conflito: null,
        payload: {
          id: "rdo-1",
          maoObra: [
            {
              id: "mao-1",
              colaboradorId:
                "ada1be00-0000-4000-8000-000000000001",
              nomeColaborador: "Adalberto Canovas Neto",
              cargo: "Servente",
            },
          ],
        },
      },
      "2026-07-08T15:40:00.000Z",
      "m-1-mao-obra-repair",
    );

    expect(updated).not.toBeNull();
    expect(updated?.clientMutationId).toBe(
      "m-1-mao-obra-repair",
    );
    expect(updated?.status).toBe("PENDING");
    expect(updated?.tentativas).toBe(0);
    expect(updated?.ultimaTentativaEm).toBeNull();
    expect(updated?.criadaNoClienteEm).toBe(
      "2026-07-08T15:40:00.000Z",
    );
    expect(updated?.payload.maoObra).toEqual([
      {
        id: "mao-1",
        colaboradorId: null,
        nomeColaborador: "Adalberto Canovas Neto",
        cargo: "Servente",
      },
    ]);
    expect(updated?.ultimoErro).toContain(
      "Mão de obra preservada por nome",
    );
  });

  it("nao remove o ID quando o item nao tem nome rastreavel", () => {
    const updated = mutationAfterMaoObraReferenceRepair(
      {
        ...baseMutation,
        status: "ERROR",
        ultimoErro:
          "fk_rdo_mao_obra_colaborador FOREIGN KEY (`colaborador_id`)",
        conflito: null,
        payload: {
          maoObra: [
            {
              id: "mao-1",
              colaboradorId:
                "ada1be00-0000-4000-8000-000000000001",
            },
          ],
        },
      },
      "2026-07-08T15:40:00.000Z",
    );

    expect(updated).toBeNull();
  });
});

describe("rdoAfterMaoObraReferenceRepair", () => {
  it("mantem o RDO local coerente com a mao de obra reparada", () => {
    const updated = rdoAfterMaoObraReferenceRepair(
      {
        ...baseRdo,
        payload: {
          maoObra: [
            {
              colaboradorId:
                "ada1be00-0000-4000-8000-000000000001",
              nomeColaborador: "Adalberto Canovas Neto",
            },
          ],
        },
      },
      "2026-07-08T15:40:00.000Z",
    );

    expect(updated?.syncStatus).toBe("PENDING_SYNC");
    expect(updated?.updatedAt).toBe("2026-07-08T15:40:00.000Z");
    expect(updated?.payload.maoObra).toEqual([
      {
        colaboradorId: null,
        nomeColaborador: "Adalberto Canovas Neto",
      },
    ]);
  });
});

const CONFLICT_OBRA_ID = "00000000-0000-4000-8000-000000000041";
const CONFLICT_DEVICE_ID = "00000000-0000-4000-8000-000000000042";

function canonicalMutation(
  id: string,
  status: CanonicalOutboxMutationRecord["status"],
): CanonicalOutboxMutationRecord {
  return {
    contractVersion: 13,
    clientMutationId: id,
    entidadeTipo: "RDO",
    entidadeId: `entity-${id}`,
    operacao: "ATUALIZAR_RDO_RASCUNHO",
    baseVersao: 1,
    payload: {
      id: `entity-${id}`,
      titulo: "Versão local",
      nested: { preserved: true },
    },
    status,
    tentativas: status === "SYNCING" ? 1 : 0,
    ultimaTentativaEm: status === "SYNCING"
      ? "2026-07-17T12:01:00.000Z"
      : null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: "2026-07-17T12:00:00.000Z",
    updatedAt: "2026-07-17T12:01:00.000Z",
    transport: "SYNC_PUSH",
    dependsOnMutationIds: [],
    correlationId: `correlation-${id}`,
    fieldPatch: {
      changed: { titulo: "Versão local" },
      baseValues: { titulo: "Versão base" },
    },
    trace: {
      actorId: "actor-1",
      deviceId: CONFLICT_DEVICE_ID,
      authorizationScope: [CONFLICT_OBRA_ID],
      correlationId: `correlation-${id}`,
      causationId: null,
      ontologyEventId: `event-${id}`,
      payloadHash: "a".repeat(64),
    },
    nextAttemptAt: null,
    blockedReason: null,
  };
}

function canonicalEvent(
  mutation: CanonicalOutboxMutationRecord,
): CanonicalOperationalEventRecord {
  return {
    contractVersion: 13,
    id: mutation.trace.ontologyEventId,
    type: "RDO_EDITADO",
    principalEntity: { tipo: "RDO", id: mutation.entidadeId },
    principalEntityKey: `RDO:${mutation.entidadeId}`,
    relatedEntities: [],
    obraId: CONFLICT_OBRA_ID,
    rdoId: mutation.entidadeId,
    colaboradorId: "actor-1",
    occurredAt: mutation.criadaNoClienteEm,
    syncedAt: null,
    origin: "OFFLINE",
    responsibleUserId: "actor-1",
    responsibleUserName: "Ana",
    payload: mutation.payload,
    syncStatus: "PENDING_SYNC",
    schemaVersion: 1,
    clientMutationId: mutation.clientMutationId,
    deviceId: mutation.trace.deviceId,
    correlationId: mutation.trace.correlationId,
    causationId: mutation.trace.causationId,
    previousState: { titulo: "Versão base" },
    newState: { titulo: "Versão local" },
    result: mutation.status === "SYNCING" ? "SYNCING" : "PENDING",
    errorCategory: null,
    entityVersion: mutation.baseVersao,
  };
}

describe("applyPushResultAtomically conflict preservation", () => {
  let databaseName = "";

  beforeEach(async () => {
    vi.stubGlobal("BroadcastChannel", undefined);
    const ownerId = crypto.randomUUID();
    setSession({
      colaboradorId: ownerId,
      nome: "Operador de campo",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: [CONFLICT_OBRA_ID],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
    databaseName = await databaseNameForScope(
      ownerId,
      `BETA:${CONFLICT_OBRA_ID}`,
    );
  });

  afterEach(async () => {
    await closeCortexDb();
    if (databaseName) {
      await deleteDB(databaseName);
    }
    clearSession();
    vi.unstubAllGlobals();
  });

  it("keeps the payload, stores field triples and marks only the correlated event as conflict", async () => {
    const database = await getCortexDb();
    const conflicted = canonicalMutation("conflicted", "SYNCING");
    const independent = canonicalMutation("independent", "PENDING");
    const structuredConflict = {
      titulo: {
        base: "Versão base",
        local: "Versão local",
        remote: "Versão remota",
      },
    };

    await Promise.all([
      database.put("outbox_mutations", conflicted),
      database.put("outbox_mutations", independent),
      database.put("operational_events", canonicalEvent(conflicted)),
      database.put("operational_events", canonicalEvent(independent)),
    ]);

    await applyPushResultAtomically({
      clientMutationId: conflicted.clientMutationId,
      status: "DESCARTADA",
      entidadeTipo: conflicted.entidadeTipo,
      entidadeId: conflicted.entidadeId,
      conflito: structuredConflict,
      erro: "Conflito de campo.",
    });

    const storedConflict = await database.get(
      "outbox_mutations",
      conflicted.clientMutationId,
    );
    expect(storedConflict).toMatchObject({
      status: "CONFLICT",
      payload: conflicted.payload,
      conflito: structuredConflict,
    });
    expect(await database.getAll("outbox_mutations")).toHaveLength(2);
    expect(await database.get(
      "operational_events",
      conflicted.trace.ontologyEventId,
    )).toMatchObject({
      clientMutationId: conflicted.clientMutationId,
      result: "CONFLICT",
    });
    expect(await database.get(
      "operational_events",
      independent.trace.ontologyEventId,
    )).toMatchObject({
      clientMutationId: independent.clientMutationId,
      result: "PENDING",
    });
    expect(selectReadyOutboxMutations(
      await database.getAll("outbox_mutations"),
      100,
    )).toEqual([independent]);
  });
});
