import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { clearSession, setSession } from "../auth/authSession";
import {
  closeCortexDb,
  getCortexDb,
  initializeCortexDb,
} from "../../lib/db/cortexDb";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import { updateSyncState } from "../../lib/db/syncStateRepository";
import {
  rdoDraftFromLocalRecord,
  saveExistingRdoDraftAtomically,
  saveNewRdoDraftAtomically,
} from "../../lib/db/localRdoService";
import { canonicalMutationJson } from "../../lib/sync/mutationEnvelope";
import { createEmptyMaoObra, createEmptyRdo } from "./createEmptyRdo";
import {
  createAndPersistRdoDraft,
} from "./rdoDraftCreation";
import type { RdoCreationContextLookup } from "./rdoLookupApi";

const USER_ID = "00000000-0000-4000-8000-000000000010";
const DEVICE_ID = "00000000-0000-4000-8000-000000000020";
const OBRA_ID = "00000000-0000-4000-8000-000000000001";
const RDO_ID = "00000000-0000-4000-8000-000000000030";
const SOURCE_RDO_ID = "00000000-0000-4000-8000-000000000031";
const SOURCE_MUTATION_ID = "00000000-0000-4000-8000-000000000032";
let databaseName = "";

function complete(total = 0) {
  return { status: "COMPLETE", total, returned: total, complete: true };
}

function context(): RdoCreationContextLookup {
  return {
    obra: {
      id: OBRA_ID,
      codigoContrato: "CTR-A",
      codigoCw: "CW-A",
      nome: "Obra A",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "ATIVA",
      version: 4,
    },
    data: "2026-07-22",
    nextNumberSuggestion: "RDO-0021",
    previousRdo: {
      id: SOURCE_RDO_ID,
      numeroRdo: "RDO-0020",
      dataRdo: "2026-07-21",
      status: "RASCUNHO",
      version: 0,
    },
    previousWorkforce: [],
    programacoes: [],
    colaboradores: [],
    equipamentos: [],
    coverage: {
      previousWorkforce: complete(),
      programacoes: complete(),
      colaboradores: complete(),
      equipamentos: complete(),
      serviceCatalog: { status: "NOT_CONFIGURED", total: 0, returned: 0, complete: false },
      priceCatalog: { status: "NOT_CONFIGURED", total: 0, returned: 0, complete: false },
    },
    freshness: {
      status: "FRESH",
      sourceVersion: 5,
      generatedAt: "2026-07-22T12:00:00.000Z",
      staleAfter: "2026-07-22T12:15:00.000Z",
    },
    provenance: {
      receiptVersion: 7,
      sourceVersion: 5,
      worksiteId: OBRA_ID,
      selectedDate: "2026-07-22",
      previousRdoId: SOURCE_RDO_ID,
      generatedAt: "2026-07-22T12:00:00.000Z",
    },
  };
}

beforeEach(async () => {
  setSession({
    colaboradorId: USER_ID,
    nome: "Encarregado",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(USER_ID, `BETA:${OBRA_ID}`);
  await initializeCortexDb();
  await updateSyncState({ deviceId: DEVICE_ID, usuarioId: USER_ID });
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

describe("transação inicial do novo RDO", () => {
  it("persiste draft, mutação canônica e evento correlacionado antes de retornar", async () => {
    const database = await getCortexDb();
    await database.put("outbox_mutations", {
      clientMutationId: SOURCE_MUTATION_ID,
      entidadeTipo: "RDO",
      entidadeId: SOURCE_RDO_ID,
      operacao: "CRIAR_RDO",
      baseVersao: null,
      payload: {},
      status: "PENDING",
      tentativas: 0,
      ultimaTentativaEm: null,
      ultimoErro: null,
      conflito: null,
      criadaNoClienteEm: "2026-07-21T12:00:00.000Z",
      updatedAt: "2026-07-21T12:00:00.000Z",
    });

    const created = await createAndPersistRdoDraft(context(), {
      draftId: RDO_ID,
      occurredAt: "2026-07-22T12:01:00.000Z",
    });

    const storedRdo = await database.get("rdos", RDO_ID);
    const storedMutation = await database.get(
      "outbox_mutations",
      created.mutation.clientMutationId,
    );
    const storedEvents = await database.getAllFromIndex(
      "operational_events",
      "by-client-mutation-id",
      created.mutation.clientMutationId,
    );
    expect(created.draft).toMatchObject({
      id: RDO_ID,
      obraId: OBRA_ID,
      syncStatus: "PENDING_SYNC",
      creationContextVersion: 7,
    });
    expect(storedRdo).toMatchObject({ id: RDO_ID, syncStatus: "PENDING_SYNC" });
    expect(storedMutation).toMatchObject({
      schemaVersion: 13,
      entidadeId: RDO_ID,
      operacao: "CRIAR_RDO",
      dependsOnMutationIds: [SOURCE_MUTATION_ID],
      payload: expect.objectContaining({
        id: RDO_ID,
        obraId: OBRA_ID,
        creationContextVersion: 7,
      }),
    });
    expect(storedEvents).toEqual([
      expect.objectContaining({
        schemaVersion: 13,
        type: "RDO_CRIADO",
        clientMutationId: created.mutation.clientMutationId,
        result: "PENDING",
      }),
    ]);
  });

  it("não deixa mutação ou evento órfão se o UUID local já existir", async () => {
    const first = await createAndPersistRdoDraft(context(), { draftId: RDO_ID });
    await expect(
      createAndPersistRdoDraft(context(), { draftId: RDO_ID }),
    ).rejects.toThrow();

    const database = await getCortexDb();
    expect(await database.getAllFromIndex("outbox_mutations", "by-entity-id", RDO_ID))
      .toHaveLength(1);
    expect(await database.getAllFromIndex(
      "operational_events",
      "by-client-mutation-id",
      first.mutation.clientMutationId,
    )).toHaveLength(1);
  });

  it("substitui de forma imutável a criação canônica ao editar antes do primeiro sync", async () => {
    const created = await createAndPersistRdoDraft(context(), {
      draftId: RDO_ID,
      occurredAt: "2026-07-22T12:01:00.000Z",
    });
    const immutableOriginal = canonicalMutationJson(created.mutation);
    const editedDraft = {
      ...created.draft,
      observacoes: "Frente liberada pelo encarregado",
      maoObra: [
        {
          ...createEmptyMaoObra(),
          localId: "00000000-0000-4000-8000-000000000040",
          colaboradorId: "00000000-0000-4000-8000-000000000041",
          nomeColaborador: "Ana Operadora",
          cargo: "Operadora",
          origin: "AUTHORIZED_CONTEXT" as const,
          availability: "AVAILABLE" as const,
          selected: true,
        },
      ],
    };

    const edited = await saveExistingRdoDraftAtomically(editedDraft);
    const database = await getCortexDb();
    const original = await database.get(
      "outbox_mutations",
      created.mutation.clientMutationId,
    );
    const replacement = await database.get(
      "outbox_mutations",
      edited.mutation.clientMutationId,
    );

    expect(original).toMatchObject({
      status: "REJECTED",
      lastSafeCode: "SUPERSEDED_BY_LOCAL_EDIT",
    });
    expect(canonicalMutationJson({
      payload: original?.payload,
      trace: original?.schemaVersion === 13 ? original.trace : null,
      fieldPatch:
        original?.schemaVersion === 13 ? original.fieldPatch : null,
    })).toBe(canonicalMutationJson({
      payload: created.mutation.payload,
      trace: created.mutation.trace,
      fieldPatch: created.mutation.fieldPatch,
    }));
    expect(immutableOriginal).not.toBe(canonicalMutationJson(replacement));
    expect(replacement).toMatchObject({
      schemaVersion: 13,
      operation: "CREATE",
      operacao: "CRIAR_RDO",
      causationId: created.mutation.clientMutationId,
      correlationId: created.mutation.correlationId,
      payload: expect.objectContaining({
        observacoes: "Frente liberada pelo encarregado",
        maoObra: [
          expect.objectContaining({
            colaboradorId: "00000000-0000-4000-8000-000000000041",
          }),
        ],
      }),
    });
    expect(replacement?.dependsOnMutationIds).not.toContain(
      created.mutation.clientMutationId,
    );
    const activeCreates = (
      await database.getAllFromIndex(
        "outbox_mutations",
        "by-entity-id",
        RDO_ID,
      )
    ).filter(
      (mutation) =>
        mutation.operacao === "CRIAR_RDO" &&
        ["PENDING", "ERROR", "SYNCING"].includes(mutation.status),
    );
    expect(activeCreates).toEqual([
      expect.objectContaining({
        clientMutationId: edited.mutation.clientMutationId,
      }),
    ]);
    const replacementEvents = await database.getAllFromIndex(
        "operational_events",
        "by-client-mutation-id",
        edited.mutation.clientMutationId,
      );
    expect(replacementEvents).toEqual([
      expect.objectContaining({
        type: "RDO_EDITADO",
        result: "PENDING",
        causationId: created.mutation.clientMutationId,
        newState: expect.objectContaining({
          observacoes: "Frente liberada pelo encarregado",
        }),
      }),
    ]);
    const storedRdo = await database.get("rdos", RDO_ID);
    expect(storedRdo).toMatchObject({
      payload: expect.objectContaining({
        observacoes: "Frente liberada pelo encarregado",
      }),
    });
    expect(
      await database.getAllFromIndex("rdoMaoObra", "by-rdo-id", RDO_ID),
    ).toEqual([
      expect.objectContaining({
        localId: "00000000-0000-4000-8000-000000000040",
        payload: expect.objectContaining({
          nomeColaborador: "Ana Operadora",
        }),
      }),
    ]);
    expect(rdoDraftFromLocalRecord(storedRdo!)).toMatchObject({
      observacoes: "Frente liberada pelo encarregado",
      maoObra: [
        expect.objectContaining({
          colaboradorId: "00000000-0000-4000-8000-000000000041",
          selected: true,
        }),
      ],
    });
  });

  it("vincula draft importado ao contexto sem alterar células operacionais importadas", async () => {
    const imported = createEmptyRdo();
    imported.id = RDO_ID;
    imported.dataRdo = "2026-07-22";
    imported.numeroRdo = "RDO-PLANILHA-77";
    imported.contrato = "CONTRATO-LIDO-DO-XLSX";
    imported.observacoes = "Ocorrência preservada do arquivo";
    imported.kmInicialProgramado = "12,300";
    imported.equipamentos = [
      {
        localId: "00000000-0000-4000-8000-000000000050",
        assetId: "",
        prefixo: "EQ-PLANILHA",
        descricao: "Vibroacabadora importada",
        tipoEquipamento: "",
        tipoVinculo: "",
        quantidade: 1,
        horaInicio: "07:00",
        horaFim: "17:00",
        observacoes: "linha 14",
      },
    ];

    const created = await createAndPersistRdoDraft(context(), {
      baseDraft: imported,
      occurredAt: "2026-07-22T12:02:00.000Z",
    });

    expect(created.draft).toMatchObject({
      id: RDO_ID,
      obraId: OBRA_ID,
      dataRdo: "2026-07-22",
      creationContextVersion: 7,
      numeroRdo: "RDO-PLANILHA-77",
      contrato: "CONTRATO-LIDO-DO-XLSX",
      observacoes: "Ocorrência preservada do arquivo",
      kmInicialProgramado: "12,300",
      equipamentos: [
        expect.objectContaining({
          prefixo: "EQ-PLANILHA",
          descricao: "Vibroacabadora importada",
          observacoes: "linha 14",
        }),
      ],
    });
    expect(created.mutation.payload).toMatchObject({
      obraId: OBRA_ID,
      creationContextVersion: 7,
      observacoes: "Ocorrência preservada do arquivo",
    });
  });

  it("nunca cria envelope push-ready sem receipt de contexto", async () => {
    const draft = createEmptyRdo();
    draft.id = RDO_ID;
    draft.obraId = OBRA_ID;
    draft.dataRdo = "2026-07-22";
    draft.creationContextVersion = null;

    await expect(saveNewRdoDraftAtomically(draft)).rejects.toThrow(
      "Contexto versionado da obra é obrigatório para criar o RDO.",
    );
    const database = await getCortexDb();
    expect(
      await database.getAllFromIndex("outbox_mutations", "by-entity-id", RDO_ID),
    ).toEqual([]);
    expect(await database.get("rdos", RDO_ID)).toBeUndefined();
  });

  it("mantém editável um RDO legado já versionado no servidor sem receipt", async () => {
    const database = await getCortexDb();
    const draft = createEmptyRdo();
    draft.id = RDO_ID;
    draft.obraId = OBRA_ID;
    draft.numeroRdo = "RDO-LEGADO-7";
    draft.dataRdo = "2026-07-20";
    draft.creationContextVersion = null;
    draft.observacoes = "Evidência legada atualizada";
    await database.put("rdos", {
      id: RDO_ID,
      obraId: OBRA_ID,
      programacaoId: null,
      numeroRdo: "RDO-LEGADO-7",
      dataRdo: "2026-07-20",
      statusRdo: "RASCUNHO",
      syncStatus: "SYNCED",
      versaoEntidade: 7,
      payload: { observacoes: "criado antes do receipt v48" },
      createdAt: "2026-07-20T12:00:00.000Z",
      updatedAt: "2026-07-20T12:00:00.000Z",
    });

    const updated = await saveExistingRdoDraftAtomically(draft);

    expect(updated.mutation).toMatchObject({
      operacao: "ATUALIZAR_RDO_RASCUNHO",
      baseVersao: 7,
      blockedReason: null,
      status: "PENDING",
    });
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      versaoEntidade: 7,
      syncStatus: "PENDING_SYNC",
      payload: expect.objectContaining({
        creationContextVersion: null,
        observacoes: "Evidência legada atualizada",
      }),
    });
  });
});
