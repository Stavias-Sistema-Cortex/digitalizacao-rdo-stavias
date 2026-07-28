import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  apiFetch: vi.fn(),
  readResponseBody: vi.fn(),
  responseErrorMessage: vi.fn(),
}));

vi.mock("../../lib/api/apiClient", () => ({
  apiFetch: mocks.apiFetch,
  readResponseBody: mocks.readResponseBody,
  responseErrorMessage: mocks.responseErrorMessage,
}));

import {
  buscarContextoDeCriacaoRdo,
  buscarContextoParaRascunhoRdo,
  buscarColaboradoresAutorizadosDaObra,
  buscarRdoAutoritativoPorId,
  RDO_CREATION_CONTEXT_INCOMPATIBLE,
} from "./rdoLookupApi";

describe("buscarColaboradoresAutorizadosDaObra", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.apiFetch.mockResolvedValue({
      ok: true,
      status: 200,
    } as Response);
  });

  it("usa endpoint escopado sem data nem receipt", async () => {
    mocks.readResponseBody.mockResolvedValue({
      ids: ["colaborador-1"],
      total: 1,
      complete: true,
    });

    await expect(
      buscarColaboradoresAutorizadosDaObra("obra antiga"),
    ).resolves.toEqual({
      ids: ["colaborador-1"],
      total: 1,
      complete: true,
    });
    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/obras/obra%20antiga/colaboradores/autorizados",
    );
  });

  it("rejeita cobertura truncada ou IDs duplicados", async () => {
    mocks.readResponseBody.mockResolvedValue({
      ids: ["colaborador-1", "colaborador-1"],
      total: 501,
      complete: false,
    });
    await expect(
      buscarColaboradoresAutorizadosDaObra("obra-1"),
    ).rejects.toThrow("cobertura");

    mocks.readResponseBody.mockResolvedValue({
      ids: ["colaborador-1", " colaborador-1 "],
      total: 2,
      complete: true,
    });
    await expect(
      buscarColaboradoresAutorizadosDaObra("obra-1"),
    ).rejects.toThrow("cobertura");
  });
});

describe("buscarRdoAutoritativoPorId", () => {
  const RDO_ID = "00000000-0000-4000-8000-000000000201";
  const OBRA_ID = "00000000-0000-4000-8000-000000000202";

  beforeEach(() => {
    vi.clearAllMocks();
  });

  function completeRdo(
    overrides: Record<string, unknown> = {},
  ): Record<string, unknown> {
    return {
      id: RDO_ID,
      obraId: OBRA_ID,
      programacaoId: null,
      numeroRdo: "RDO-0001",
      dataRdo: "2026-07-28",
      previousRdoId: null,
      creationContextVersion: 8,
      clientMutationId: "mutation-original",
      versaoEntidade: 4,
      apontadorColaboradorId: null,
      diaSemana: "terça-feira",
      cliente: null,
      contrato: null,
      rodovia: null,
      cidade: null,
      uf: null,
      kmInicialProgramado: null,
      kmFinalProgramado: null,
      kmInicialInterditado: null,
      kmFinalInterditado: null,
      turno: null,
      horaInicio: null,
      horaFim: null,
      condicaoManha: null,
      condicaoTarde: null,
      condicaoNoite: null,
      pluviometriaMm: null,
      status: "RASCUNHO",
      observacoes: null,
      preenchidoPor: null,
      apontadorRdo: null,
      encarregadoObra: null,
      fiscalizacaoCampo: null,
      maoObra: [],
      equipamentos: [],
      materiais: [],
      controlesGeometricos: [],
      servicosExecutados: [],
      alocacoesColaboradores: [],
      attachments: [],
      ...overrides,
    };
  }

  it("exige a versão autoritativa no snapshot encontrado", async () => {
    mocks.apiFetch.mockResolvedValue({
      ok: true,
      status: 200,
    } as Response);
    mocks.readResponseBody.mockResolvedValue(completeRdo());

    await expect(buscarRdoAutoritativoPorId(RDO_ID)).resolves.toEqual({
      kind: "FOUND",
      version: 4,
      rdo: expect.objectContaining({ id: RDO_ID, obraId: OBRA_ID }),
    });
  });

  it("distingue 404 comprovado de resposta 200 incompleta", async () => {
    mocks.apiFetch.mockResolvedValueOnce({
      ok: false,
      status: 404,
    } as Response);
    mocks.readResponseBody.mockResolvedValueOnce(null);
    await expect(buscarRdoAutoritativoPorId(RDO_ID)).resolves.toEqual({
      kind: "MISSING",
    });

    mocks.apiFetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
    } as Response);
    mocks.readResponseBody.mockResolvedValueOnce({
      id: RDO_ID,
      obraId: OBRA_ID,
    });
    await expect(buscarRdoAutoritativoPorId(RDO_ID)).resolves.toEqual({
      kind: "INCOMPLETE",
    });
  });

  it("rejeita 200 sem o snapshot completo usado na reconciliação", async () => {
    mocks.apiFetch.mockResolvedValue({
      ok: true,
      status: 200,
    } as Response);
    const partial = completeRdo();
    delete partial.maoObra;

    mocks.readResponseBody.mockResolvedValue(partial);

    await expect(buscarRdoAutoritativoPorId(RDO_ID)).resolves.toEqual({
      kind: "INCOMPLETE",
    });
  });

  it.each([
    "maoObra",
    "equipamentos",
    "materiais",
    "controlesGeometricos",
    "servicosExecutados",
    "alocacoesColaboradores",
    "attachments",
  ])("rejeita item incompleto em %s", async (collection) => {
    mocks.apiFetch.mockResolvedValue({
      ok: true,
      status: 200,
    } as Response);
    mocks.readResponseBody.mockResolvedValue(
      completeRdo({ [collection]: [{}] }),
    );

    await expect(buscarRdoAutoritativoPorId(RDO_ID)).resolves.toEqual({
      kind: "INCOMPLETE",
    });
  });

  it.each([
    { creationContextVersion: null },
    { dataRdo: "28/07/2026" },
    { dataRdo: "2026-02-30" },
  ])("rejeita cabeçalho autoritativo inválido: %o", async (override) => {
    mocks.apiFetch.mockResolvedValue({
      ok: true,
      status: 200,
    } as Response);
    mocks.readResponseBody.mockResolvedValue(completeRdo(override));

    await expect(buscarRdoAutoritativoPorId(RDO_ID)).resolves.toEqual({
      kind: "INCOMPLETE",
    });
  });

  it("rejeita IDs vazios ou duplicados dentro das coleções", async () => {
    mocks.apiFetch.mockResolvedValue({
      ok: true,
      status: 200,
    } as Response);
    const worker = {
      id: "worker-1",
      colaboradorId: null,
      nomeColaborador: "Equipe nominal",
      cargo: null,
      tipoVinculo: null,
      quantidade: 1,
      horaInicio: null,
      horaFim: null,
      observacoes: null,
      origemItemId: null,
    };
    mocks.readResponseBody.mockResolvedValue(
      completeRdo({ maoObra: [worker, { ...worker }] }),
    );
    await expect(buscarRdoAutoritativoPorId(RDO_ID)).resolves.toEqual({
      kind: "INCOMPLETE",
    });

    mocks.readResponseBody.mockResolvedValue(
      completeRdo({ maoObra: [{ ...worker, id: "   " }] }),
    );
    await expect(buscarRdoAutoritativoPorId(RDO_ID)).resolves.toEqual({
      kind: "INCOMPLETE",
    });

    mocks.readResponseBody.mockResolvedValue(
      completeRdo({
        maoObra: [worker, { ...worker, id: " worker-1 " }],
      }),
    );
    await expect(buscarRdoAutoritativoPorId(RDO_ID)).resolves.toEqual({
      kind: "INCOMPLETE",
    });
  });
});

describe("buscarContextoDeCriacaoRdo", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.apiFetch.mockResolvedValue({ ok: true, status: 200 } as Response);
  });

  it("rejeita no limite HTTP uma cobertura legada incompatível", async () => {
    mocks.readResponseBody.mockResolvedValue({
      obra: { id: "obra-autorizada" },
      data: "2026-07-22",
      previousRdo: null,
      previousWorkforce: [],
      programacoes: [],
      colaboradores: [],
      equipamentos: [],
      serviceCatalog: [],
      coverage: {},
      freshness: {
        status: "FRESH",
        sourceVersion: 8,
        generatedAt: "2026-07-22T12:00:00.000Z",
        staleAfter: "2026-07-22T12:15:00.000Z",
      },
      provenance: {
        receiptVersion: 4,
        sourceVersion: 8,
        worksiteId: "obra-autorizada",
        selectedDate: "2026-07-22",
        previousRdoId: null,
        generatedAt: "2026-07-22T12:00:00.000Z",
      },
    });

    await expect(
      buscarContextoDeCriacaoRdo("obra-autorizada", "2026-07-22"),
    ).rejects.toThrow(RDO_CREATION_CONTEXT_INCOMPATIBLE);
  });
});

describe("buscarContextoParaRascunhoRdo", () => {
  const OBRA_ID = "00000000-0000-4000-8000-000000000101";
  const PREVIOUS_RDO_ID = "00000000-0000-4000-8000-000000000102";
  const WORKER_ID = "00000000-0000-4000-8000-000000000103";
  const WORKFORCE_ITEM_ID = "00000000-0000-4000-8000-000000000104";

  beforeEach(() => {
    vi.clearAllMocks();
  });

  function legacyContext() {
    return {
      obra: {
        id: OBRA_ID,
        codigoContrato: "CTR-101",
        codigoCw: "CW101",
        nome: "Obra legada autorizada",
        cliente: "Cliente real",
        cidade: "Iracemápolis",
        uf: "SP",
        rodovia: "SP-151",
        status: "ATIVA",
      },
      data: "2026-07-22",
      programacoes: [],
      colaboradores: [],
      equipamentos: [],
    };
  }

  function installHttpFixtures(
    fixtures: Record<string, unknown>,
  ) {
    const bodies = new WeakMap<object, unknown>();
    mocks.apiFetch.mockImplementation(async (path: string) => {
      const response = {
        ok: true,
        status: 200,
        requestPath: path,
      } as unknown as Response;
      bodies.set(response, fixtures[path]);
      return response;
    });
    mocks.readResponseBody.mockImplementation(
      async (response: Response) => bodies.get(response),
    );
  }

  it("aceita somente o formato legado reconhecido e carrega o último RDO real com equipe escopada", async () => {
    installHttpFixtures({
      [`/rdos/contexto?obraId=${OBRA_ID}&data=2026-07-22`]:
        legacyContext(),
      [`/rdos?obraId=${OBRA_ID}`]: [
        {
          id: "00000000-0000-4000-8000-000000000105",
          obraId: OBRA_ID,
          numeroRdo: "RDO-DO-DIA",
          dataRdo: "2026-07-22",
          status: "RASCUNHO",
        },
        {
          id: PREVIOUS_RDO_ID,
          obraId: OBRA_ID,
          numeroRdo: "RDO-ANTERIOR",
          dataRdo: "2026-07-21",
          status: "ENVIADO",
        },
      ],
      [`/rdos/${PREVIOUS_RDO_ID}`]: {
        id: PREVIOUS_RDO_ID,
        obraId: OBRA_ID,
        numeroRdo: "RDO-ANTERIOR",
        dataRdo: "2026-07-21",
        status: "ENVIADO",
        maoObra: [
          {
            id: WORKFORCE_ITEM_ID,
            colaboradorId: WORKER_ID,
            nomeColaborador: "Ana Operadora",
            cargo: "Operadora",
            tipoVinculo: "PRÓPRIO",
            quantidade: 1,
            horaInicio: "07:00:00",
            horaFim: "17:00:00",
            observacoes: "Equipe real do RDO anterior",
          },
        ],
      },
      [`/obras/${OBRA_ID}/colaboradores`]: [
        {
          id: WORKER_ID,
          nome: "Ana Operadora",
          cpfMascarado: "***.***.***-**",
          nomePerfil: "Operadora",
          nomeGrupo: "Campo",
        },
      ],
    });

    const resolved = await buscarContextoParaRascunhoRdo(
      OBRA_ID,
      "2026-07-22",
    );

    expect(resolved).toMatchObject({
      kind: "LOCAL_PENDING",
      context: {
        obra: {
          id: OBRA_ID,
          codigoContrato: "CTR-101",
          cidade: "Iracemápolis",
        },
        data: "2026-07-22",
        previousRdo: {
          id: PREVIOUS_RDO_ID,
          numeroRdo: "RDO-ANTERIOR",
          dataRdo: "2026-07-21",
        },
        previousWorkforce: [
          {
            sourceItemId: WORKFORCE_ITEM_ID,
            sourceRdoId: PREVIOUS_RDO_ID,
            collaboratorId: WORKER_ID,
            nameSnapshot: "Ana Operadora",
            availability: "AVAILABLE",
          },
        ],
        colaboradores: [
          {
            id: WORKER_ID,
            nome: "Ana Operadora",
            nomePerfil: "Operadora",
          },
        ],
      },
    });
    expect(resolved.context).not.toHaveProperty("provenance");
    expect(resolved.context).not.toHaveProperty("receiptVersion");
  });

  it("não converte resposta canônica malformada em fallback legado", async () => {
    installHttpFixtures({
      [`/rdos/contexto?obraId=${OBRA_ID}&data=2026-07-22`]: {
        ...legacyContext(),
        coverage: {},
        freshness: {},
        provenance: {},
      },
    });

    await expect(
      buscarContextoParaRascunhoRdo(OBRA_ID, "2026-07-22"),
    ).rejects.toThrow(RDO_CREATION_CONTEXT_INCOMPATIBLE);
    expect(mocks.apiFetch).toHaveBeenCalledTimes(1);
  });

  it("rejeita obra ou data divergente antes de consultar RDOs relacionados", async () => {
    installHttpFixtures({
      [`/rdos/contexto?obraId=${OBRA_ID}&data=2026-07-22`]: {
        ...legacyContext(),
        obra: { ...legacyContext().obra, id: "obra-fora-do-escopo" },
      },
    });

    await expect(
      buscarContextoParaRascunhoRdo(OBRA_ID, "2026-07-22"),
    ).rejects.toThrow(RDO_CREATION_CONTEXT_INCOMPATIBLE);
    expect(mocks.apiFetch).toHaveBeenCalledTimes(1);
  });
});
