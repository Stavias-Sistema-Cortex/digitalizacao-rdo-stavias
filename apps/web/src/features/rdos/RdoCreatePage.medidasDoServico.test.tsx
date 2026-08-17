// @vitest-environment jsdom

import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createEmptyRdo, createEmptyServicoExecutado } from "./createEmptyRdo";
import type { RdoCreationContextLookup } from "./rdoLookupApi";

const mocks = vi.hoisted(() => ({
  listRdoAttachments: vi.fn(),
  getCachedContext: vi.fn(),
  requireContext: vi.fn(),
  getLocalRdo: vi.fn(),
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

vi.mock("../../lib/db/localRdoService", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/db/localRdoService")>()),
  rascunhoDifereDoQueEstaGravado: vi.fn().mockResolvedValue(false),
}));

vi.mock("./useRdoLocalPersistence", () => ({
  useRdoLocalPersistence: () => ({
    isSaving: false,
    isSyncing: false,
    message: "",
    error: "",
    saveLocally: vi.fn(),
    synchronize: vi.fn(),
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
    coverage: {
      previousWorkforce: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      programacoes: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      colaboradores: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      equipamentos: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      serviceCatalog: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      priceCatalog: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
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

function renderizarComUnidade(unidade: string) {
  render(
    <RdoCreatePage
      initialDraft={{
        ...createEmptyRdo(),
        id: "rdo-na-tela",
        obraId: "obra-a",
        dataRdo: "2026-07-22",
        syncStatus: "PENDING_SYNC" as const,
        servicosExecutados: [
          {
            ...createEmptyServicoExecutado(),
            localId: "servico-1",
            serviceId: "service-cbuq",
            priceVersionId: "preco-1",
            servicoNome: "PAV-002 · CBUQ",
            unidade,
            trechoInicial: "12+000",
            trechoFinal: "12+605",
            larguraM: 4.55,
            espessuraM: 0.03,
          },
        ],
      }}
      isExisting
      creationContext={context()}
      onBackToList={vi.fn()}
      onSaved={vi.fn()}
    />,
  );
}

/** A caixa da métrica cujo rótulo começa pelo nome dado. */
function medida(nome: string): HTMLElement {
  const caixa = screen
    .getByText(nome)
    .closest(".calculated-metric");
  if (!caixa) throw new Error(`Métrica não encontrada: ${nome}`);
  return caixa as HTMLElement;
}

beforeEach(() => {
  mocks.listRdoAttachments.mockResolvedValue([]);
  mocks.getCachedContext.mockResolvedValue(undefined);
  mocks.requireContext.mockReset();
  mocks.getLocalRdo.mockReset();
  mocks.getLocalRdo.mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

/**
 * As três medidas saem do mesmo trecho, mas só uma é cobrada.
 *
 * <p>A que a unidade do contrato nomeia era marcada por um "· quantidade"
 * colado ao rótulo, do mesmo tamanho e da mesma cor que as outras duas: três
 * números de peso igual, e nenhuma pista de qual ia para a medição.
 */
describe("as medidas calculadas do serviço", () => {
  it.each([
    ["m", "Comprimento"],
    ["m2", "Área"],
    ["m3", "Volume"],
  ])("marca a medida que a unidade %s cobra", (unidade, esperada) => {
    renderizarComUnidade(unidade);

    expect(
      within(medida(esperada)).getByText("quantidade"),
    ).toBeVisible();
    expect(medida(esperada).className).toContain(
      "calculated-metric--quantidade",
    );
  });

  it("deixa as outras duas como apoio, sem marca", () => {
    renderizarComUnidade("m3");

    for (const apoio of ["Comprimento", "Área"]) {
      expect(within(medida(apoio)).queryByText("quantidade")).toBeNull();
      expect(medida(apoio).className).not.toContain(
        "calculated-metric--quantidade",
      );
    }
  });

  /*
   * Unidade que não sai do trecho — tonelada, hora, verba — não tem medida a
   * cobrar, e marcar qualquer uma das três afirmaria o que o contrato não diz.
   */
  it("não marca nada quando a unidade não sai do trecho", () => {
    renderizarComUnidade("t");

    expect(screen.queryByText("quantidade")).toBeNull();
    expect(
      screen.getByText(/Este serviço é medido em t, que não sai do trecho/),
    ).toBeVisible();
  });

  it("mostra as três medidas com a unidade em que foram calculadas", () => {
    renderizarComUnidade("m3");

    expect(within(medida("Comprimento")).getByText("605 m")).toBeVisible();
    expect(within(medida("Área")).getByText("2.752,75 m²")).toBeVisible();
    expect(within(medida("Volume")).getByText("82,583 m³")).toBeVisible();
  });
});
