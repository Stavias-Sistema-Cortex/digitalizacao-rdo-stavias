// @vitest-environment jsdom

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  createEmptyRdo,
  createEmptyServicoExecutado,
} from "./createEmptyRdo";
import type {
  RdoContextServicePriceChoice,
  RdoCreationContextLookup,
} from "./rdoLookupApi";

const ordem: string[] = [];

const mocks = vi.hoisted(() => ({
  listRdoAttachments: vi.fn(),
  getCachedContext: vi.fn(),
  requireContext: vi.fn(),
  getLocalRdo: vi.fn(),
  saveLocally: vi.fn(),
  synchronize: vi.fn(),
  rascunhoDiferente: vi.fn(),
}));

vi.mock("../../lib/db/rdoAttachmentRepository", () => ({
  listRdoAttachments: mocks.listRdoAttachments,
  markRdoAttachmentRemoved: vi.fn(),
  putRdoAttachment: vi.fn(),
}));

vi.mock("./rdoCreationContextRepository", () => ({
  getCachedRdoCreationContext: mocks.getCachedContext,
  requireRdoCreationContext: mocks.requireContext,
}));

vi.mock("../../lib/db/rdoRepository", () => ({
  getLocalRdo: mocks.getLocalRdo,
}));

vi.mock("../../lib/db/localRdoService", () => ({
  rascunhoDifereDoQueEstaGravado: mocks.rascunhoDiferente,
  servicoExecutadoNeedsCatalogSelection: (item: {
    servicoNome: string;
    serviceId: string;
    unidade: string;
  }) => item.servicoNome.trim() !== "" && (
    item.serviceId.trim() === "" || item.unidade.trim() === ""
  ),
}));

vi.mock("./useRdoLocalPersistence", () => ({
  useRdoLocalPersistence: () => ({
    isSaving: false,
    isSyncing: false,
    message: "",
    error: "",
    saveLocally: mocks.saveLocally,
    synchronize: mocks.synchronize,
  }),
}));

vi.mock("./RdoWorkforceEditor", () => ({
  RdoWorkforceEditor: () => <section aria-label="Equipe" />,
}));

import { RdoCreatePage } from "./RdoCreatePage";

function context(): RdoCreationContextLookup {
  return {
    obra: {
      id: "obra-a",
      codigoContrato: "CTR-A",
      codigoCw: "CW-A",
      nome: "Obra A",
      cliente: "Cliente",
      cidade: "Cidade",
      uf: "SP",
      rodovia: "BR-1",
      status: "ATIVA",
      version: 4,
      kmInicialEixo: null,
      kmFinalEixo: null,
    },
    data: "2026-07-22",
    nextNumberSuggestion: "RDO-1",
    previousRdo: null,
    previousWorkforce: [],
    programacoes: [],
    colaboradores: [],
    equipamentos: [],
    serviceCatalog: [],
    priceCatalog: [],
    coverage: {
      previousWorkforce: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      programacoes: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      colaboradores: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      equipamentos: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      serviceCatalog: { status: "NOT_CONFIGURED", complete: false, total: 0, returned: 0 },
      priceCatalog: { status: "NOT_CONFIGURED", complete: false, total: 0, returned: 0 },
    },
    provenance: {
      receiptVersion: 16,
      sourceVersion: 4,
      worksiteId: "obra-a",
      selectedDate: "2026-07-22",
      previousRdoId: null,
      generatedAt: "2026-07-22T10:00:00Z",
    },
    freshness: {
      status: "FRESH",
      sourceVersion: 4,
      generatedAt: "2026-07-22T10:00:00Z",
      staleAfter: "2026-07-22T11:00:00Z",
    },
  };
}

function draft() {
  return {
    ...createEmptyRdo(),
    id: "rdo-na-tela",
    obraId: "obra-a",
    dataRdo: "2026-07-22",
    syncStatus: "PENDING_SYNC" as const,
  };
}

function renderizar(
  initialDraft = draft(),
  creationContext = context(),
) {
  render(
    <RdoCreatePage
      initialDraft={initialDraft}
      isExisting
      creationContext={creationContext}
      onBackToList={vi.fn()}
      onSaved={vi.fn()}
    />,
  );
}

function contextoComServico(
  priceChoices: RdoContextServicePriceChoice[],
): RdoCreationContextLookup {
  const base = context();
  return {
    ...base,
    serviceCatalog: [{
      id: "service-fresagem",
      code: "PAV-001",
      name: "Fresagem",
      description: "Fresagem do pavimento",
      priceChoices,
    }],
    priceCatalog: priceChoices,
    coverage: {
      ...base.coverage,
      serviceCatalog: {
        status: "COMPLETE",
        complete: true,
        total: 1,
        returned: 1,
      },
      priceCatalog: {
        status: "COMPLETE",
        complete: true,
        total: priceChoices.length,
        returned: priceChoices.length,
      },
    },
  };
}

function opcaoDePreco(
  id: string,
  unit: string,
): RdoContextServicePriceChoice {
  return {
    id,
    serviceId: "service-fresagem",
    unit,
    version: 1,
    validFrom: "2026-07-01",
    effectiveValidTo: null,
  };
}

function rascunhoComLinhaDeServico() {
  const initialDraft = draft();
  initialDraft.servicosExecutados = [{
    ...createEmptyServicoExecutado(),
    localId: "servico-fresagem",
  }];
  return initialDraft;
}

async function selecionarFresagem() {
  fireEvent.focus(screen.getByRole("combobox", {
    name: "Tipo de serviço",
  }));
  fireEvent.click(await screen.findByRole("option", {
    name: /Fresagem/i,
  }));
}

function apertarSincronizar() {
  fireEvent.click(screen.getByRole("button", { name: "Sincronizar agora" }));
}

beforeEach(() => {
  ordem.length = 0;
  mocks.listRdoAttachments.mockResolvedValue([]);
  mocks.getCachedContext.mockResolvedValue(undefined);
  mocks.requireContext.mockReset();
  mocks.getLocalRdo.mockReset();
  mocks.getLocalRdo.mockResolvedValue(undefined);
  mocks.saveLocally.mockReset();
  mocks.saveLocally.mockImplementation(async () => {
    ordem.push("gravou");
  });
  mocks.synchronize.mockReset();
  mocks.synchronize.mockImplementation(async () => {
    ordem.push("sincronizou");
  });
  mocks.rascunhoDiferente.mockReset();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

/**
 * "Sincronizar agora" promete mandar embora o que está na tela.
 *
 * <p>Enviar sem gravar cumpria a promessa pela metade: subia a última versão
 * salva e devolvia "sincronização concluída" para quem tinha acabado de digitar
 * algo que continuava só no aparelho — verdadeiro sobre a fila, falso sobre a
 * intenção de quem apertou o botão.
 */
describe("sincronizar manda o que está na tela", () => {
  it("grava antes de enviar quando há edição não salva", async () => {
    mocks.rascunhoDiferente.mockResolvedValue(true);
    renderizar();

    apertarSincronizar();

    await waitFor(() => {
      expect(ordem).toEqual(["gravou", "sincronizou"]);
    });
  });

  /*
   * Gravar sempre enfileiraria uma edição vazia a cada toque: o servidor a
   * aplica, ela consome uma versão da entidade e aparece na Memória como se
   * alguém tivesse mexido no RDO. Três toques, três edições inventadas.
   */
  it("não grava quando não há nada de novo, e ainda assim sincroniza", async () => {
    mocks.rascunhoDiferente.mockResolvedValue(false);
    renderizar();

    apertarSincronizar();

    await waitFor(() => {
      expect(mocks.synchronize).toHaveBeenCalledOnce();
    });
    expect(mocks.saveLocally).not.toHaveBeenCalled();
  });

  /*
   * Falhou a gravação, não envia. Subir o estado antigo logo depois de a pessoa
   * ver um erro de salvamento é a pior das duas metades: ela fica achando que
   * mandou o que está vendo.
   */
  it("não envia nada se a gravação falhar", async () => {
    mocks.rascunhoDiferente.mockResolvedValue(true);
    mocks.saveLocally.mockRejectedValue(new Error("disco cheio"));
    renderizar();

    apertarSincronizar();

    await waitFor(() => {
      expect(mocks.saveLocally).toHaveBeenCalledOnce();
    });
    expect(mocks.synchronize).not.toHaveBeenCalled();
  });
});

describe("serviço sem catálogo", () => {
  it("não marca a seção como concluída nem entrega a linha à sincronização", async () => {
    const initialDraft = draft();
    initialDraft.servicosExecutados = [{
      ...createEmptyServicoExecutado(),
      localId: "servico-sem-catalogo",
      servicoNome: "Fresagem",
      serviceId: "",
    }];

    renderizar(initialDraft);

    expect(screen.getByRole("link", {
      name: /Serviços.*Seção pendente/i,
    })).toBeVisible();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Selecione no catálogo um serviço com unidade válida antes de sincronizar esta linha.",
    );

    mocks.rascunhoDiferente.mockResolvedValue(true);
    apertarSincronizar();

    await waitFor(() => {
      expect(mocks.saveLocally).toHaveBeenCalledOnce();
    });
    expect(mocks.synchronize).not.toHaveBeenCalled();
  });
});

describe("unidade vinda do catálogo de preços", () => {
  it.each([
    ["não há preço vigente", []],
    [
      "há unidades concorrentes",
      [
        opcaoDePreco("price-m2", "M2"),
        opcaoDePreco("price-m3", "M3"),
      ],
    ],
  ])(
    "retém a linha quando %s",
    async (_scenario, priceChoices) => {
      mocks.rascunhoDiferente.mockResolvedValue(true);
      renderizar(
        rascunhoComLinhaDeServico(),
        contextoComServico(priceChoices),
      );

      await selecionarFresagem();

      expect(screen.getByRole("alert")).toHaveTextContent(
        "Selecione no catálogo um serviço com unidade válida antes de sincronizar esta linha.",
      );
      apertarSincronizar();

      await waitFor(() => {
        expect(mocks.saveLocally).toHaveBeenCalledWith(
          expect.objectContaining({
            servicosExecutados: [expect.objectContaining({
              serviceId: "service-fresagem",
              priceVersionId: "",
              unidade: "",
            })],
          }),
        );
      });
      expect(mocks.synchronize).not.toHaveBeenCalled();
    },
  );

  it("sincroniza duas versões legítimas da mesma unidade sem inventar um preço", async () => {
    mocks.rascunhoDiferente.mockResolvedValue(true);
    renderizar(
      rascunhoComLinhaDeServico(),
      contextoComServico([
        opcaoDePreco("price-m2-v1", "M2"),
        opcaoDePreco("price-m2-v2", "M2"),
      ]),
    );

    await selecionarFresagem();
    apertarSincronizar();

    await waitFor(() => {
      expect(mocks.saveLocally).toHaveBeenCalledWith(
        expect.objectContaining({
          servicosExecutados: [expect.objectContaining({
            serviceId: "service-fresagem",
            priceVersionId: "",
            unidade: "M2",
          })],
        }),
      );
      expect(mocks.synchronize).toHaveBeenCalledOnce();
    });
  });
});
