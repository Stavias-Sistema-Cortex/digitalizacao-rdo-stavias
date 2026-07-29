import { describe, expect, it } from "vitest";

import {
  createEmptyAlocacaoColaborador,
  createEmptyMaoObra,
  createEmptyRdo,
} from "./createEmptyRdo";
import {
  RDO_CONTEXT_OFFLINE_MISSING,
  addAuthorizedWorker,
  applyRdoCreationContext,
  contextPresentation,
  nextRosterFocusIndex,
  removeRosterMember,
  setRosterApontador,
  setRosterSelected,
  shouldApplyRemoteContext,
} from "./rdoCreationContext";
import type { RdoCreationContextLookup } from "./rdoLookupApi";

const OBRA_ID = "00000000-0000-4000-8000-000000000001";
const COLABORADOR_A = "00000000-0000-4000-8000-000000000011";
const COLABORADOR_B = "00000000-0000-4000-8000-000000000012";

function complete(total: number) {
  return { status: "COMPLETE", total, returned: total, complete: true };
}

function context(
  overrides: Partial<RdoCreationContextLookup> = {},
): RdoCreationContextLookup {
  return {
    obra: {
      id: OBRA_ID,
      codigoContrato: "CTR-41",
      codigoCw: "CW-41",
      nome: "Conservação SP-041",
      cliente: "DER",
      cidade: "Campinas",
      uf: "SP",
      rodovia: "SP-041",
      status: "ATIVA",
      version: 7,
    },
    data: "2026-07-22",
    nextNumberSuggestion: "RDO-0042",
    previousRdo: {
      id: "00000000-0000-4000-8000-000000000099",
      numeroRdo: "RDO-0041",
      dataRdo: "2026-07-21",
      status: "ENVIADO",
      version: 9,
    },
    previousWorkforce: [
      {
        sourceItemId: "item-a",
        sourceRdoId: "00000000-0000-4000-8000-000000000099",
        collaboratorId: COLABORADOR_A,
        nameSnapshot: "Ana",
        roleSnapshot: "Apontadora",
        linkType: "PROPRIO",
        quantity: 1,
        startTime: "07:00:00",
        endTime: "17:00:00",
        observations: "Equipe anterior",
        availability: "AVAILABLE",
      },
      {
        sourceItemId: "item-b",
        sourceRdoId: "00000000-0000-4000-8000-000000000099",
        collaboratorId: COLABORADOR_B,
        nameSnapshot: "Bruno",
        roleSnapshot: "Operador",
        linkType: "TERCEIRIZADO",
        quantity: 1,
        startTime: null,
        endTime: null,
        observations: null,
        availability: "AVAILABLE",
      },
    ],
    programacoes: [],
    colaboradores: [
      {
        id: COLABORADOR_A,
        codigoColaborador: "001",
        nome: "Ana",
        papelNaObra: "APONTADOR",
        nomePerfil: "Apontadora",
      },
      {
        id: COLABORADOR_B,
        codigoColaborador: "002",
        nome: "Bruno",
        papelNaObra: "OPERACIONAL",
        nomePerfil: "Operador",
      },
    ],
    equipamentos: [],
    serviceCatalog: [],
    coverage: {
      previousWorkforce: complete(2),
      programacoes: complete(0),
      colaboradores: complete(2),
      equipamentos: complete(0),
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
      sourceVersion: 11,
      generatedAt: "2026-07-22T12:00:00.000Z",
      staleAfter: "2026-07-22T12:15:00.000Z",
    },
    provenance: {
      receiptVersion: 17,
      sourceVersion: 11,
      worksiteId: OBRA_ID,
      selectedDate: "2026-07-22",
      previousRdoId: "00000000-0000-4000-8000-000000000099",
      generatedAt: "2026-07-22T12:00:00.000Z",
    },
    ...overrides,
  };
}

describe("contexto obra-data do novo RDO", () => {
  it("preenche a obra e clona toda a equipe anterior com IDs locais novos", () => {
    const ids = ["local-a", "local-b"];
    const draft = applyRdoCreationContext(
      createEmptyRdo(),
      context(),
      () => ids.shift() ?? "unexpected",
    );

    expect(draft).toMatchObject({
      obraId: OBRA_ID,
      dataRdo: "2026-07-22",
      numeroRdo: "RDO-0042",
      previousRdoId: "00000000-0000-4000-8000-000000000099",
      creationContextVersion: 17,
      cliente: "DER",
      contrato: "CTR-41",
      rodovia: "SP-041",
      cidade: "Campinas",
      uf: "SP",
    });
    expect(draft.maoObra).toEqual([
      expect.objectContaining({
        localId: "local-a",
        origemItemId: "item-a",
        sourceRdoId: "00000000-0000-4000-8000-000000000099",
        origin: "PREVIOUS_RDO",
        colaboradorId: COLABORADOR_A,
        selected: true,
        horaInicio: "07:00",
        horaFim: "17:00",
      }),
      expect.objectContaining({
        localId: "local-b",
        origemItemId: "item-b",
        sourceRdoId: "00000000-0000-4000-8000-000000000099",
        origin: "PREVIOUS_RDO",
        colaboradorId: COLABORADOR_B,
        selected: true,
      }),
    ]);
  });

  it("usa o predecessor canônico do mesmo dia no RDO seguinte", () => {
    const sameDay = context({
      previousRdo: {
        id: "same-day",
        numeroRdo: "RDO-0042-A",
        dataRdo: "2026-07-22",
        status: "RASCUNHO",
        version: 1,
      },
      previousWorkforce: [
        {
          ...context().previousWorkforce[0],
          sourceItemId: "same-day-worker",
          sourceRdoId: "same-day",
        },
      ],
      provenance: {
        ...context().provenance,
        previousRdoId: "same-day",
      },
    });
    const draft = applyRdoCreationContext(createEmptyRdo(), sameDay);

    expect(draft.previousRdoId).toBe("same-day");
    expect(draft.maoObra).toEqual([
      expect.objectContaining({
        origemItemId: "same-day-worker",
        sourceRdoId: "same-day",
      }),
    ]);
  });

  it("não usa o próprio RDO aberto como origem ao reaplicar contexto", () => {
    const current = createEmptyRdo();
    current.id = "same-day";
    const sameDay = context({
      previousRdo: {
        id: current.id,
        numeroRdo: "RDO-0042-A",
        dataRdo: "2026-07-22",
        status: "RASCUNHO",
        version: 1,
      },
      provenance: {
        ...context().provenance,
        previousRdoId: current.id,
      },
    });

    const draft = applyRdoCreationContext(current, sameDay);

    expect(draft.previousRdoId).toBe("");
    expect(draft.maoObra).toEqual([]);
  });

  it("permite desmarcar, adicionar apenas autorizado e manter zero ou um apontador", () => {
    let draft = applyRdoCreationContext(createEmptyRdo(), context());
    const ana = draft.maoObra.find((item) => item.colaboradorId === COLABORADOR_A)!;
    draft = setRosterSelected(draft, ana.localId, false);
    expect(draft.maoObra.find((item) => item.localId === ana.localId)?.selected).toBe(false);

    draft = addAuthorizedWorker(
      { ...draft, maoObra: draft.maoObra.filter((item) => item.colaboradorId !== COLABORADOR_A) },
      COLABORADOR_A,
      context().colaboradores,
      () => "added-a",
    );
    expect(draft.maoObra.at(-1)).toMatchObject({
      localId: "added-a",
      colaboradorId: COLABORADOR_A,
      selected: true,
      origin: "AUTHORIZED_CONTEXT",
    });
    expect(() =>
      addAuthorizedWorker(draft, "not-authorized", context().colaboradores),
    ).toThrow("Colaborador não autorizado para esta obra.");

    draft = setRosterApontador(draft, COLABORADOR_A);
    expect(draft.apontadorColaboradorId).toBe(COLABORADOR_A);
    draft = setRosterApontador(draft, COLABORADOR_B);
    expect(draft.apontadorColaboradorId).toBe(COLABORADOR_B);
    draft = setRosterSelected(
      draft,
      draft.maoObra.find((item) => item.colaboradorId === COLABORADOR_B)!.localId,
      false,
    );
    expect(draft.apontadorColaboradorId).toBe("");
  });

  it("remove um colaborador herdado e limpa o apontador quando necessário", () => {
    let draft = applyRdoCreationContext(createEmptyRdo(), context());
    const ana = draft.maoObra.find(
      (item) => item.colaboradorId === COLABORADOR_A,
    )!;
    draft = setRosterApontador(draft, COLABORADOR_A);

    draft = removeRosterMember(draft, ana.localId);

    expect(draft.maoObra).not.toEqual(
      expect.arrayContaining([
        expect.objectContaining({ colaboradorId: COLABORADOR_A }),
      ]),
    );
    expect(draft.apontadorColaboradorId).toBe("");
    expect(draft.apontadorRdo).toBe("");
  });

  it("remove rateio dependente ao desmarcar ou excluir colaborador canônico", () => {
    let draft = applyRdoCreationContext(createEmptyRdo(), context());
    const ana = draft.maoObra.find(
      (item) => item.colaboradorId === COLABORADOR_A,
    )!;
    const bruno = draft.maoObra.find(
      (item) => item.colaboradorId === COLABORADOR_B,
    )!;
    draft.alocacoesColaboradores = [
      {
        ...createEmptyAlocacaoColaborador(),
        localId: "rateio-ana",
        colaboradorId: COLABORADOR_A,
      },
      {
        ...createEmptyAlocacaoColaborador(),
        localId: "rateio-bruno",
        colaboradorId: COLABORADOR_B,
      },
    ];

    draft = setRosterSelected(draft, ana.localId, false);
    expect(draft.alocacoesColaboradores).toEqual([
      expect.objectContaining({ colaboradorId: COLABORADOR_B }),
    ]);

    draft = removeRosterMember(draft, bruno.localId);
    expect(draft.alocacoesColaboradores).toEqual([]);
  });

  it("não apaga apontador textual legado ao remover mão de obra manual", () => {
    const manual = {
      ...createEmptyMaoObra(),
      localId: "manual-row",
      nomeColaborador: "Maria Servente",
      availability: "AVAILABLE" as const,
    };
    const draft = {
      ...createEmptyRdo(),
      apontadorColaboradorId: "",
      apontadorRdo: "Apontador registrado no documento",
      maoObra: [manual],
    };

    const deselected = setRosterSelected(draft, manual.localId, false);
    expect(deselected.apontadorRdo).toBe(
      "Apontador registrado no documento",
    );

    const removed = removeRosterMember(draft, manual.localId);
    expect(removed.apontadorRdo).toBe(
      "Apontador registrado no documento",
    );
  });

  it("não aplica resposta remota sobre edição feita durante a requisição", () => {
    expect(
      shouldApplyRemoteContext({
        isExisting: false,
        requestedKey: `${OBRA_ID}|2026-07-22`,
        currentKey: `${OBRA_ID}|2026-07-22`,
        revisionAtRequest: 3,
        currentRevision: 4,
      }),
    ).toBe(false);
    expect(
      shouldApplyRemoteContext({
        isExisting: true,
        requestedKey: `${OBRA_ID}|2026-07-22`,
        currentKey: `${OBRA_ID}|2026-07-22`,
        revisionAtRequest: 3,
        currentRevision: 3,
      }),
    ).toBe(false);
  });

  it("expõe receipt e expiração sem fingir atualidade", () => {
    expect(
      contextPresentation(context(), new Date("2026-07-22T12:10:00.000Z")),
    ).toMatchObject({ status: "FRESH", receiptVersion: 17 });
    expect(
      contextPresentation(context(), new Date("2026-07-22T12:20:00.000Z")),
    ).toMatchObject({
      status: "STALE",
      label: "Desatualizado",
      receiptVersion: 17,
    });
    expect(
      contextPresentation(
        context({
          coverage: {
            ...context().coverage,
            colaboradores: {
              status: "PARTIAL",
              complete: false,
              total: 3,
              returned: 2,
            },
          },
        }),
        new Date("2026-07-22T12:10:00.000Z"),
      ),
    ).toMatchObject({ status: "PARTIAL", label: "Parcial" });
    expect(RDO_CONTEXT_OFFLINE_MISSING).toBe(
      "Contexto desta obra ainda não está disponível offline.",
    );
  });

  it("navega a tabela por teclado sem escapar dos limites", () => {
    expect(nextRosterFocusIndex("ArrowDown", 0, 3)).toBe(1);
    expect(nextRosterFocusIndex("ArrowUp", 0, 3)).toBe(2);
    expect(nextRosterFocusIndex("Home", 2, 3)).toBe(0);
    expect(nextRosterFocusIndex("End", 0, 3)).toBe(2);
  });
});
