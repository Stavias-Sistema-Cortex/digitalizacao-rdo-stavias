import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  clearSession,
  setOfflineSession,
  setSession,
} from "../auth/authSession";
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
import {
  applyPushResultAtomically,
  markMutationAsSyncing,
} from "../../lib/sync/syncStorage";
import { createEmptyMaoObra, createEmptyRdo } from "./createEmptyRdo";
import {
  createAndPersistRdoDraft,
} from "./rdoDraftCreation";
import { putRdoCreationContext } from "./rdoCreationContextRepository";
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
    serviceCatalog: [],
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
  await putRdoCreationContext(context());
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

describe("transação inicial do novo RDO", () => {
  function canonicalContextDraft(id = RDO_ID) {
    const draft = createEmptyRdo();
    draft.id = id;
    draft.obraId = OBRA_ID;
    draft.dataRdo = "2026-07-22";
    draft.creationContextVersion = 7;
    draft.numeroRdo = "RDO-0021";
    draft.contrato = "CTR-A";
    draft.previousRdoId = SOURCE_RDO_ID;
    return draft;
  }

  async function expectNoCreateWrites(rdoId = RDO_ID) {
    const database = await getCortexDb();
    expect(await database.get("rdos", rdoId)).toBeUndefined();
    expect(
      await database.getAllFromIndex("outbox_mutations", "by-entity-id", rdoId),
    ).toEqual([]);
    expect(
      await database.getAllFromIndex("operational_events", "by-rdo-id", rdoId),
    ).toEqual([]);
  }

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

    await markMutationAsSyncing(replacement!);
    await applyPushResultAtomically({
      clientMutationId: replacement!.clientMutationId,
      status: "APLICADA",
      resultado: { versaoEntidade: 1 },
    });
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      syncStatus: "SYNCED",
      versaoEntidade: 1,
      payload: expect.objectContaining({
        observacoes: "Frente liberada pelo encarregado",
      }),
    });
  });

  it("vincula draft importado ao contexto sem alterar células operacionais importadas", async () => {
    const imported = createEmptyRdo();
    imported.id = RDO_ID;
    imported.obraId = "obra-lida-da-planilha";
    imported.dataRdo = "2026-06-18";
    imported.numeroRdo = "RDO-PLANILHA-77";
    imported.cliente = "CLIENTE-LIDO-DO-XLSX";
    imported.contrato = "CONTRATO-LIDO-DO-XLSX";
    imported.rodovia = "RODOVIA-LIDA-DO-XLSX";
    imported.cidade = "CIDADE-LIDA-DO-XLSX";
    imported.uf = "RJ";
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

    const selectedContext = context();
    selectedContext.obra.cliente = "Cliente canônico da obra";
    selectedContext.obra.codigoContrato = "CTR-CANONICO";
    selectedContext.obra.rodovia = "SP-041";
    selectedContext.obra.cidade = "Campinas";
    selectedContext.obra.uf = "SP";
    await putRdoCreationContext(selectedContext);
    const created = await createAndPersistRdoDraft(selectedContext, {
      baseDraft: imported,
      occurredAt: "2026-07-22T12:02:00.000Z",
    });

    expect(created.draft).toMatchObject({
      id: RDO_ID,
      obraId: OBRA_ID,
      dataRdo: "2026-07-22",
      creationContextVersion: 7,
      numeroRdo: "RDO-0021",
      cliente: "Cliente canônico da obra",
      contrato: "CTR-CANONICO",
      rodovia: "SP-041",
      cidade: "Campinas",
      uf: "SP",
      observacoes: "Ocorrência preservada do arquivo",
      kmInicialProgramado: "12,300",
      equipamentos: [
        expect.objectContaining({
          prefixo: "EQ-PLANILHA",
          descricao: "Vibroacabadora importada",
          observacoes: "linha 14",
        }),
      ],
      importEvidence: {
        source: "IMPORTED_DOCUMENT",
        rawWorksiteIdentity: {
          numeroRdo: "RDO-PLANILHA-77",
          obraId: "obra-lida-da-planilha",
          dataRdo: "2026-06-18",
          cliente: "CLIENTE-LIDO-DO-XLSX",
          contrato: "CONTRATO-LIDO-DO-XLSX",
          rodovia: "RODOVIA-LIDA-DO-XLSX",
          cidade: "CIDADE-LIDA-DO-XLSX",
          uf: "RJ",
        },
        boundContext: {
          obraId: OBRA_ID,
          dataRdo: "2026-07-22",
          receiptVersion: 7,
        },
      },
    });
    expect(created.mutation.payload).toMatchObject({
      obraId: OBRA_ID,
      creationContextVersion: 7,
      cliente: "Cliente canônico da obra",
      contrato: "CTR-CANONICO",
      rodovia: "SP-041",
      cidade: "Campinas",
      uf: "SP",
      observacoes: "Ocorrência preservada do arquivo",
    });
    expect(created.mutation.payload).not.toHaveProperty("importEvidence");
    const database = await getCortexDb();
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      payload: expect.objectContaining({
        importEvidence: expect.objectContaining({
          rawWorksiteIdentity: expect.objectContaining({
            numeroRdo: "RDO-PLANILHA-77",
            contrato: "CONTRATO-LIDO-DO-XLSX",
          }),
        }),
      }),
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
    await expectNoCreateWrites();
  });

  it("rejeita receipt positivo forjado sem qualquer write de CREATE", async () => {
    const draft = createEmptyRdo();
    draft.id = RDO_ID;
    draft.obraId = OBRA_ID;
    draft.dataRdo = "2026-07-22";
    draft.creationContextVersion = 999;

    await expect(saveNewRdoDraftAtomically(draft)).rejects.toThrow(
      "Contexto versionado da obra é obrigatório para criar o RDO.",
    );
    await expectNoCreateWrites();
  });

  it("rejeita cache ausente ou pertencente a outro owner sem writes", async () => {
    const database = await getCortexDb();
    await database.delete("rdo_creation_contexts", [
      USER_ID,
      OBRA_ID,
      "2026-07-22",
    ]);
    await database.put("rdo_creation_contexts", {
      ownerId: "00000000-0000-4000-8000-000000000099",
      obraId: OBRA_ID,
      selectedDate: "2026-07-22",
      sourceVersion: 5,
      receiptVersion: 7,
      cachedAt: "2026-07-22T12:00:00.000Z",
      coverage: context().coverage as unknown as Record<string, unknown>,
      context: context() as unknown as Record<string, unknown>,
    });
    const draft = createEmptyRdo();
    draft.id = RDO_ID;
    draft.obraId = OBRA_ID;
    draft.dataRdo = "2026-07-22";
    draft.creationContextVersion = 7;

    await expect(saveNewRdoDraftAtomically(draft)).rejects.toThrow(
      "Contexto versionado da obra é obrigatório para criar o RDO.",
    );
    await expectNoCreateWrites();
  });

  it("rejeita obra ou data sem o cache exato da sessão", async () => {
    const wrongWorksite = createEmptyRdo();
    wrongWorksite.id = "00000000-0000-4000-8000-000000000036";
    wrongWorksite.obraId = "00000000-0000-4000-8000-000000000099";
    wrongWorksite.dataRdo = "2026-07-22";
    wrongWorksite.creationContextVersion = 7;
    const wrongDate = createEmptyRdo();
    wrongDate.id = "00000000-0000-4000-8000-000000000037";
    wrongDate.obraId = OBRA_ID;
    wrongDate.dataRdo = "2026-07-23";
    wrongDate.creationContextVersion = 7;

    for (const draft of [wrongWorksite, wrongDate]) {
      await expect(saveNewRdoDraftAtomically(draft)).rejects.toThrow(
        "Contexto versionado da obra é obrigatório para criar o RDO.",
      );
      await expectNoCreateWrites(draft.id);
    }
  });

  it("rejeita provenance incoerente e cobertura obrigatória ou opcional parcial", async () => {
    const database = await getCortexDb();
    const invalidContexts = [
      {
        ...context(),
        provenance: {
          ...context().provenance,
          worksiteId: "00000000-0000-4000-8000-000000000099",
        },
      },
      {
        ...context(),
        provenance: {
          ...context().provenance,
          selectedDate: "2026-07-21",
        },
      },
      {
        ...context(),
        coverage: {
          ...context().coverage,
          colaboradores: {
            status: "PARTIAL",
            complete: false,
            total: 2,
            returned: 1,
          },
        },
      },
      {
        ...context(),
        provenance: {
          ...context().provenance,
          sourceVersion: 6,
        },
      },
      {
        ...context(),
        coverage: {
          ...context().coverage,
          serviceCatalog: {
            status: "PARTIAL",
            complete: false,
            total: 2,
            returned: 1,
          },
        },
      },
      {
        ...context(),
        coverage: {
          ...context().coverage,
          priceCatalog: {
            status: "PARTIAL",
            complete: false,
            total: 1,
            returned: 0,
          },
        },
      },
    ];

    for (const [index, invalidContext] of invalidContexts.entries()) {
      await database.put("rdo_creation_contexts", {
        ownerId: USER_ID,
        obraId: OBRA_ID,
        selectedDate: "2026-07-22",
        sourceVersion: 5,
        receiptVersion: 7,
        cachedAt: "2026-07-22T12:00:00.000Z",
        coverage: invalidContext.coverage as unknown as Record<string, unknown>,
        context: invalidContext as unknown as Record<string, unknown>,
      });
      const draft = createEmptyRdo();
      draft.id = `${RDO_ID.slice(0, -1)}${index + 3}`;
      draft.obraId = OBRA_ID;
      draft.dataRdo = "2026-07-22";
      draft.creationContextVersion = 7;

      await expect(saveNewRdoDraftAtomically(draft)).rejects.toThrow(
        "Contexto versionado da obra é obrigatório para criar o RDO.",
      );
      await expectNoCreateWrites(draft.id);
    }
  });

  it("rejeita sessão com escopo revogado antes de gravar o CREATE", async () => {
    setSession({
      colaboradorId: USER_ID,
      nome: "Encarregado",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: [],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
    const draft = createEmptyRdo();
    draft.id = RDO_ID;
    draft.obraId = OBRA_ID;
    draft.dataRdo = "2026-07-22";
    draft.creationContextVersion = 7;

    await expect(saveNewRdoDraftAtomically(draft)).rejects.toThrow(
      "Contexto versionado da obra é obrigatório para criar o RDO.",
    );
    setSession({
      colaboradorId: USER_ID,
      nome: "Encarregado",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: [OBRA_ID],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
    await expectNoCreateWrites();
  });

  it("rejeita freshness não autoritativo, ISO inválido e janela temporal impossível", async () => {
    const invalidContexts = [
      (() => {
        const value = context();
        value.freshness.status = "LOCAL_PENDING";
        return value;
      })(),
      (() => {
        const value = context();
        value.freshness.status = "PARTIAL";
        return value;
      })(),
      (() => {
        const value = context();
        value.freshness.status = "ARBITRARY";
        return value;
      })(),
      (() => {
        const value = context();
        value.freshness.status = "STALE";
        return value;
      })(),
      (() => {
        const value = context();
        value.freshness.staleAfter = value.freshness.generatedAt;
        return value;
      })(),
      (() => {
        const value = context();
        value.freshness.staleAfter = "2026-07-22T11:59:59.999Z";
        return value;
      })(),
      (() => {
        const value = context();
        value.freshness.generatedAt = "22/07/2026 12:00";
        value.provenance.generatedAt = "22/07/2026 12:00";
        return value;
      })(),
    ];

    for (const [index, invalidContext] of invalidContexts.entries()) {
      await putRdoCreationContext(invalidContext);
      const draft = canonicalContextDraft(
        `00000000-0000-4000-8000-${String(index + 38).padStart(12, "0")}`,
      );

      await expect(saveNewRdoDraftAtomically(draft)).rejects.toThrow(
        "Contexto versionado da obra é obrigatório para criar o RDO.",
      );
      await expectNoCreateWrites(draft.id);
    }
  });

  it("deriva stale por clock de snapshot FRESH coerente e permite criação offline", async () => {
    const stale = context();
    stale.freshness.generatedAt = "2026-07-22T12:00:00.000Z";
    stale.provenance.generatedAt = stale.freshness.generatedAt;
    stale.freshness.staleAfter = "2026-07-22T12:15:00.000Z";
    expect(Date.parse(stale.freshness.staleAfter)).toBeLessThan(Date.now());
    await putRdoCreationContext(stale);
    setOfflineSession({
      colaboradorId: USER_ID,
      nome: "Encarregado",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: [OBRA_ID],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
    const draft = canonicalContextDraft();

    const created = await saveNewRdoDraftAtomically(draft);

    expect(created.mutation).toMatchObject({
      operation: "CREATE",
      status: "PENDING",
      payload: expect.objectContaining({ creationContextVersion: 7 }),
    });
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
