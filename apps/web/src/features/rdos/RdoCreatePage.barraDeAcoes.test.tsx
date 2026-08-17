// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createEmptyRdo } from "./createEmptyRdo";
import type { RdoCreationContextLookup } from "./rdoLookupApi";

const mocks = vi.hoisted(() => ({
  listRdoAttachments: vi.fn(),
  getCachedContext: vi.fn(),
  requireContext: vi.fn(),
  getLocalRdo: vi.fn(),
  getSession: vi.fn(),
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

vi.mock("../auth/authSession", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../auth/authSession")>()),
  getSession: mocks.getSession,
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

function renderizar() {
  render(
    <RdoCreatePage
      initialDraft={{
        ...createEmptyRdo(),
        id: "rdo-na-tela",
        obraId: "obra-a",
        dataRdo: "2026-07-22",
        syncStatus: "PENDING_SYNC" as const,
      }}
      isExisting
      creationContext={context()}
      onBackToList={vi.fn()}
      onSaved={vi.fn()}
    />,
  );
}

function acoesDoRodape(): string[] {
  const barra = document.querySelector(".action-bar");
  if (!barra) throw new Error("Barra de ações não encontrada.");
  return [...barra.querySelectorAll("button")].map(
    (botao) => botao.textContent ?? "",
  );
}

beforeEach(() => {
  mocks.listRdoAttachments.mockResolvedValue([]);
  mocks.getCachedContext.mockResolvedValue(undefined);
  mocks.requireContext.mockReset();
  mocks.getLocalRdo.mockReset();
  mocks.getLocalRdo.mockResolvedValue(undefined);
  mocks.getSession.mockReturnValue({ papelAcesso: "ALFA" });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

/**
 * A barra de ações é o que se vem fazer nesta tela.
 *
 * <p>O payload cru ocupava um dos quatro botões, do mesmo tamanho e do mesmo
 * peso que "Sincronizar agora" e "Salvar localmente" — quem aponta a frente
 * lia quatro escolhas onde há duas. Ele é ferramenta de diagnóstico e foi para
 * uma gaveta fechada no rodapé.
 */
describe("a barra de ações do RDO", () => {
  it("não oferece o payload como botão, nem para quem pode vê-lo", () => {
    renderizar();

    expect(acoesDoRodape()).toEqual([
      "Descartar alterações",
      "Sincronizar agora",
      "Salvar localmente",
    ]);
  });

  it("guarda o payload numa gaveta fechada", () => {
    renderizar();

    const gaveta = screen.getByText("Payload gerado").closest("details");
    expect(gaveta).not.toBeNull();
    expect((gaveta as HTMLDetailsElement).open).toBe(false);

    fireEvent.click(screen.getByText("Payload gerado"));

    expect(document.querySelector(".json-preview pre")).not.toBeNull();
  });

  /*
   * Quem não é Alfa nunca viu o payload, e continua sem ver: a gaveta some
   * inteira em vez de abrir vazia.
   */
  it("some por completo para quem não é Alfa", () => {
    mocks.getSession.mockReturnValue({ papelAcesso: "BETA" });
    renderizar();

    expect(screen.queryByText("Payload gerado")).toBeNull();
    expect(acoesDoRodape()).toHaveLength(3);
  });
});
