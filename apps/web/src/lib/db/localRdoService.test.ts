import "fake-indexeddb/auto";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createEmptyRdo } from "../../features/rdos/createEmptyRdo";
import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "./cortexDb";
import type { LocalRdoRecord } from "./db.types";
import {
  buildRdoSyncPayloadFromLocalRecord,
  repairRdoCreateMutationsForSync,
  rdoDraftFromLocalRecord,
  saveExistingRdoDraftAtomically,
  saveNewRdoDraftAtomically,
  validateRdoDraftForSync,
} from "./localRdoService";

function validDraft() {
  const draft = createEmptyRdo();

  draft.id = "rdo-local-1";
  draft.obraId = "obra-1";
  draft.numeroRdo = "RDO-001";
  draft.dataRdo = "2026-07-03";
  draft.servicosExecutados[0] = {
    ...draft.servicosExecutados[0],
    servicoNome: "Aplicação de CBUQ",
    quantidadeExecutada: 0,
  };

  return draft;
}

describe("validateRdoDraftForSync", () => {
  it("bloqueia quantidade executada negativa antes de criar mutação offline", () => {
    const draft = validDraft();
    draft.servicosExecutados[0].quantidadeExecutada = -1;

    expect(() => validateRdoDraftForSync(draft)).toThrow(
      "A quantidade executada do serviço 1 deve ser maior ou igual a zero.",
    );
  });

  it("aceita quantidade executada zero", () => {
    expect(() =>
      validateRdoDraftForSync(validDraft()),
    ).not.toThrow();
  });
});

describe("rdoDraftFromLocalRecord", () => {
  it("normaliza payload local historico com campos nulos antes de reconstruir a mutacao", () => {
    const rdo: LocalRdoRecord = {
      id: "rdo-local-legacy",
      obraId: "obra-atual",
      programacaoId: null,
      numeroRdo: "RDO-LEG-001",
      dataRdo: "2026-07-08",
      statusRdo: "RASCUNHO",
      syncStatus: "ERROR",
      versaoEntidade: null,
      createdAt: "2026-07-08T12:00:00.000Z",
      updatedAt: "2026-07-08T12:05:00.000Z",
      payload: {
        id: "id-antigo-no-payload",
        obraId: "obra-antiga-no-payload",
        programacaoId: null,
        numeroRdo: "payload-velho",
        dataRdo: "2026-01-01",
        cliente: "Intervias",
        contrato: "INTERVIAS-2-PCT",
        rodovia: null,
        cidade: null,
        uf: null,
        kmInicialProgramado: null,
        kmFinalProgramado: null,
        kmInicialInterditado: null,
        kmFinalInterditado: null,
        preenchidoPor: null,
        apontadorRdo: null,
        encarregadoObra: null,
        fiscalizacaoCampo: null,
        servicosExecutados: [
          {
            localId: "servico-1",
            servicoNome: "Aplicacao de CBUQ",
            quantidadeExecutada: 0,
            itemContratualId: null,
            unidade: null,
            trechoInicial: null,
            trechoFinal: null,
            localizacao: null,
            turno: null,
            observacoes: null,
          },
        ],
        alocacoesColaboradores: [
          {
            localId: "alocacao-1",
            colaboradorId: null,
            equipe: null,
            servicoNome: null,
            funcao: null,
            centroCusto: null,
            fonte: null,
            observacoes: null,
          },
        ],
        maoObra: [
          {
            localId: "mao-obra-1",
            colaboradorId: null,
            nomeColaborador: "Operador",
            cargo: null,
            horaInicio: null,
            horaFim: null,
            observacoes: null,
          },
        ],
        equipamentos: [
          {
            localId: "equipamento-1",
            assetId: null,
            prefixo: null,
            descricao: "Rolo compactador",
            tipoEquipamento: null,
            horaInicio: null,
            horaFim: null,
            observacoes: null,
          },
        ],
        materiais: [
          {
            localId: "material-1",
            materialNome: null,
            unidade: null,
            quantidadePrevista: null,
            quantidadeUsinada: null,
            quantidadeAplicada: null,
            quantidadeSobra: null,
            notaFiscal: null,
            fornecedor: null,
            observacoes: null,
          },
        ],
        controlesGeometricos: [
          {
            localId: "controle-1",
            subtrecho: null,
            numero: null,
            kmInicial: null,
            kmFinal: null,
            observacoes: null,
          },
        ],
        attachments: [],
      },
    };

    const draft = rdoDraftFromLocalRecord(rdo);

    expect(draft).toMatchObject({
      id: "rdo-local-legacy",
      obraId: "obra-atual",
      programacaoId: "",
      numeroRdo: "RDO-LEG-001",
      dataRdo: "2026-07-08",
      rodovia: "",
      cidade: "",
      uf: "",
      syncStatus: "ERROR",
    });
    expect(draft.maoObra[0]).toMatchObject({
      localId: "mao-obra-1",
      colaboradorId: "",
      nomeColaborador: "Operador",
      cargo: "",
    });
    expect(draft.equipamentos[0]).toMatchObject({
      localId: "equipamento-1",
      assetId: "",
      descricao: "Rolo compactador",
    });

    expect(() =>
      buildRdoSyncPayloadFromLocalRecord(rdo),
    ).not.toThrow();
  });
});

describe("saveNewRdoDraftAtomically", () => {
  let actorId: string;
  const obraId = "00000000-0000-4000-8000-000000000082";

  beforeEach(() => {
    vi.stubGlobal("BroadcastChannel", undefined);
    actorId = crypto.randomUUID();
    setSession({
      colaboradorId: actorId,
      nome: "Operador de campo",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: [obraId],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
  });

  afterEach(async () => {
    await closeCortexDb();
    clearSession();
    vi.unstubAllGlobals();
  });

  it("persiste projeção, envelope canônico e evento correlacionado no mesmo salvamento", async () => {
    const draft = validDraft();
    draft.id = "00000000-0000-4000-8000-000000000083";
    draft.obraId = obraId;

    const saved = await saveNewRdoDraftAtomically(draft);
    const database = await getCortexDb();
    const event = await database.get(
      "operational_events",
      saved.mutation.trace?.ontologyEventId ?? "missing-event",
    );

    expect(saved.mutation).toMatchObject({
      contractVersion: 13,
      entidadeTipo: "RDO",
      entidadeId: draft.id,
      operacao: "CRIAR_RDO",
      correlationId: saved.mutation.clientMutationId,
      trace: {
        actorId,
        authorizationScope: [obraId],
        correlationId: saved.mutation.clientMutationId,
      },
    });
    expect(saved.mutation.trace?.payloadHash).toMatch(/^[a-f0-9]{64}$/);
    expect(await database.getAll("outbox_mutations")).toHaveLength(1);
    expect(await database.getAll("operational_events")).toHaveLength(1);
    expect(event).toMatchObject({
      contractVersion: 13,
      clientMutationId: saved.mutation.clientMutationId,
      correlationId: saved.mutation.correlationId,
      result: "PENDING",
    });
  });

  it("carrega o ciclo de vida das fotos no mesmo envelope canônico do RDO", async () => {
    const draft = validDraft();
    draft.id = "00000000-0000-4000-8000-000000000086";
    draft.obraId = obraId;
    draft.attachments = [
      {
        id: "00000000-0000-4000-8000-000000000087",
        rdoId: draft.id,
        obraId,
        tipo: "FOTO",
        nome: "frente-de-servico.jpg",
        nomeOriginal: "frente-de-servico.jpg",
        mimeType: "image/jpeg",
        tamanhoOriginalBytes: 1_200,
        tamanhoComprimidoBytes: 900,
        tamanhoBytes: 900,
        syncStatus: "PENDING_SYNC",
        createdAt: "2026-07-03T10:00:00.000Z",
        updatedAt: "2026-07-03T10:01:00.000Z",
        removedAt: null,
        metadata: {},
      },
      {
        id: "00000000-0000-4000-8000-000000000088",
        rdoId: draft.id,
        obraId,
        tipo: "FOTO",
        nome: "trecho-removido.jpg",
        nomeOriginal: "trecho-removido.jpg",
        mimeType: "image/jpeg",
        tamanhoOriginalBytes: 700,
        tamanhoComprimidoBytes: 700,
        tamanhoBytes: 700,
        syncStatus: "PENDING_SYNC",
        createdAt: "2026-07-03T09:00:00.000Z",
        updatedAt: "2026-07-03T09:30:00.000Z",
        removedAt: "2026-07-03T09:30:00.000Z",
        metadata: {},
      },
    ];

    const saved = await saveNewRdoDraftAtomically(draft);
    const operationalEvents = (
      saved.mutation.payload.operationalEvents as Array<{
        id: string;
        type: string;
        principalEntity: { id: string };
        occurredAt: string;
      }>
    );

    expect(operationalEvents).toEqual(expect.arrayContaining([
      expect.objectContaining({
        type: "FOTO_ADICIONADA",
        principalEntity: expect.objectContaining({
          id: "00000000-0000-4000-8000-000000000087",
        }),
        occurredAt: "2026-07-03T10:00:00.000Z",
      }),
      expect.objectContaining({
        type: "FOTO_COMPRIMIDA",
        principalEntity: expect.objectContaining({
          id: "00000000-0000-4000-8000-000000000087",
        }),
      }),
      expect.objectContaining({
        type: "FOTO_REMOVIDA",
        principalEntity: expect.objectContaining({
          id: "00000000-0000-4000-8000-000000000088",
        }),
        occurredAt: "2026-07-03T09:30:00.000Z",
      }),
    ]));

    expect(
      operationalEvents.filter((event) =>
        event.type.startsWith("FOTO_"),
      ).every((event) => event.id.trim().length > 0),
    ).toBe(true);
  });

  it("mantém os IDs dos eventos de foto ao revisar um RDO ainda pendente", async () => {
    const draft = validDraft();
    draft.id = "00000000-0000-4000-8000-000000000089";
    draft.obraId = obraId;
    draft.attachments = [
      {
        id: "00000000-0000-4000-8000-000000000090",
        rdoId: draft.id,
        obraId,
        tipo: "FOTO",
        nome: "frente-revisada.jpg",
        nomeOriginal: "frente-revisada.jpg",
        mimeType: "image/jpeg",
        tamanhoOriginalBytes: 1_400,
        tamanhoComprimidoBytes: 950,
        tamanhoBytes: 950,
        syncStatus: "PENDING_SYNC",
        createdAt: "2026-07-03T10:00:00.000Z",
        updatedAt: "2026-07-03T10:01:00.000Z",
        removedAt: null,
        metadata: {},
      },
    ];

    const first = await saveNewRdoDraftAtomically(draft);
    const revision = await saveExistingRdoDraftAtomically({
      ...draft,
      numeroRdo: "RDO-001-REV-FOTO",
    });
    const photoEventIds = (payload: Record<string, unknown>) =>
      (payload.operationalEvents as Array<{ id: string; type: string }>)
        .filter((event) => event.type.startsWith("FOTO_"))
        .map((event) => ({ id: event.id, type: event.type }));

    expect(photoEventIds(revision.mutation.payload)).toEqual(
      photoEventIds(first.mutation.payload),
    );
    expect(revision.mutation.trace?.causationId).toBe(
      first.mutation.clientMutationId,
    );
  });

  it("substitui uma criação ainda pendente por um novo evento canônico, sem reescrever sua linhagem", async () => {
    const draft = validDraft();
    draft.id = "00000000-0000-4000-8000-000000000084";
    draft.obraId = obraId;

    const first = await saveNewRdoDraftAtomically(draft);
    const revisedDraft = {
      ...draft,
      numeroRdo: "RDO-001-REV-2",
    };
    const revision = await saveExistingRdoDraftAtomically(revisedDraft);
    const database = await getCortexDb();
    const mutations = await database.getAll("outbox_mutations");
    const prior = await database.get(
      "outbox_mutations",
      first.mutation.clientMutationId,
    );
    const revisionEvent = await database.get(
      "operational_events",
      revision.mutation.trace?.ontologyEventId ?? "missing-event",
    );

    expect(mutations).toHaveLength(2);
    expect(prior).toMatchObject({
      status: "REJECTED",
      clientMutationId: first.mutation.clientMutationId,
    });
    expect(revision.mutation).toMatchObject({
      contractVersion: 13,
      operacao: "CRIAR_RDO",
      trace: {
        causationId: first.mutation.clientMutationId,
      },
    });
    expect(revisionEvent).toMatchObject({
      contractVersion: 13,
      clientMutationId: revision.mutation.clientMutationId,
      causationId: first.mutation.clientMutationId,
      result: "PENDING",
    });
  });

  it("não repara uma criação canônica reescrevendo payload, hash ou correlação", async () => {
    const draft = validDraft();
    draft.id = "00000000-0000-4000-8000-000000000085";
    draft.obraId = obraId;

    const saved = await saveNewRdoDraftAtomically(draft);
    const repaired = await repairRdoCreateMutationsForSync();
    const database = await getCortexDb();
    const after = await database.get(
      "outbox_mutations",
      saved.mutation.clientMutationId,
    );

    expect(repaired).toBe(0);
    expect(after).toEqual(saved.mutation);
  });
});
