// @vitest-environment jsdom

import type { ReactNode } from "react";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createEmptyRdo } from "./createEmptyRdo";
import type { RdoCreationContextLookup } from "./rdoLookupApi";

const mocks = vi.hoisted(() => ({
  listRdoAttachments: vi.fn(),
  getCachedContext: vi.fn(),
  requireContext: vi.fn(),
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
  RdoWorkforceEditor: ({
    collaborators,
    catalogUnavailableMessage,
  }: {
    collaborators: Array<{ nome: string }>;
    catalogUnavailableMessage?: string;
  }) => (
    <section aria-label="Equipe contextual">
      {catalogUnavailableMessage ??
        collaborators.map((item) => item.nome).join(", ")}
    </section>
  ),
}));

vi.mock("../../components/shell/CortexShell", () => ({
  CortexShell: ({ children }: { children: ReactNode }) => <>{children}</>,
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
    },
    data: "2026-07-22",
    nextNumberSuggestion: "RDO-1",
    previousRdo: null,
    previousWorkforce: [],
    programacoes: [],
    colaboradores: [
      {
        id: "worker-a",
        codigoColaborador: "001",
        nome: "Ana da Obra A",
        papelNaObra: "OPERACIONAL",
        nomePerfil: "Operadora",
      },
    ],
    equipamentos: [],
    serviceCatalog: [],
    priceCatalog: [],
    coverage: {
      previousWorkforce: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      programacoes: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      colaboradores: { status: "COMPLETE", complete: true, total: 1, returned: 1 },
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

function legacyDraft() {
  return {
    ...createEmptyRdo(),
    id: "legacy-rdo",
    obraId: "obra-a",
    dataRdo: "2026-07-22",
  };
}

beforeEach(() => {
  mocks.listRdoAttachments.mockResolvedValue([]);
  mocks.getCachedContext.mockResolvedValue(undefined);
  mocks.requireContext.mockReset();
  Object.defineProperty(window.navigator, "onLine", {
    configurable: true,
    value: true,
  });
});

afterEach(cleanup);

describe("catálogo contextual de mão de obra em RDO legado/importado", () => {
  it("adquire o contexto versionado e scoped da obra quando está online", async () => {
    mocks.requireContext.mockResolvedValue({
      source: "SERVER",
      cachedAt: "2026-07-22T10:00:00Z",
      context: context(),
    });

    render(
      <RdoCreatePage
        initialDraft={legacyDraft()}
        isExisting
        onBackToList={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    await waitFor(() => {
      expect(mocks.requireContext).toHaveBeenCalledWith(
        "obra-a",
        "2026-07-22",
        true,
      );
    });
    expect(await screen.findByText("Ana da Obra A")).toBeVisible();
  });

  it("expõe indisponibilidade literal sem usar catálogo global quando não há cache offline", async () => {
    Object.defineProperty(window.navigator, "onLine", {
      configurable: true,
      value: false,
    });
    mocks.requireContext.mockRejectedValue(
      new Error("Contexto desta obra ainda não está disponível offline."),
    );

    render(
      <RdoCreatePage
        initialDraft={legacyDraft()}
        isExisting
        onBackToList={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    expect(
      await screen.findByText(
        "Colaboradores autorizados desta obra não estão disponíveis offline.",
      ),
    ).toBeVisible();
    expect(mocks.requireContext).toHaveBeenCalledWith(
      "obra-a",
      "2026-07-22",
      false,
    );
  });
});
