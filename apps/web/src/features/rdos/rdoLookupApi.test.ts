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
  RDO_CREATION_CONTEXT_INCOMPATIBLE,
} from "./rdoLookupApi";

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
