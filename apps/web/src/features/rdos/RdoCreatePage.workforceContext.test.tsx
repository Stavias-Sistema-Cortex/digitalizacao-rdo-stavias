// @vitest-environment jsdom

import type { ReactNode } from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createEmptyRdo } from "./createEmptyRdo";
import type { RdoCreationContextLookup } from "./rdoLookupApi";

const mocks = vi.hoisted(() => ({
  listRdoAttachments: vi.fn(),
  getCachedContext: vi.fn(),
  requireContext: vi.fn(),
  getLocalRdo: vi.fn(),
  synchronize: vi.fn(),
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

vi.mock("./useRdoLocalPersistence", () => ({
  useRdoLocalPersistence: () => ({
    isSaving: false,
    isSyncing: false,
    message: "",
    error: "",
    saveLocally: vi.fn(),
    synchronize: mocks.synchronize,
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
  mocks.getLocalRdo.mockReset();
  mocks.synchronize.mockReset();
  mocks.synchronize.mockResolvedValue(undefined);
  Object.defineProperty(window.navigator, "onLine", {
    configurable: true,
    value: true,
  });
});

describe("reconciliação da identificação canônica", () => {
  it("rehydrates the read-only number after synchronization", async () => {
    const draft = legacyDraft();
    draft.numeroRdo = "RDO-STALE";
    draft.syncStatus = "PENDING_SYNC";
    mocks.getLocalRdo.mockResolvedValue({
      id: draft.id,
      obraId: draft.obraId,
      programacaoId: null,
      numeroRdo: "RDO-0042",
      dataRdo: draft.dataRdo,
      statusRdo: "RASCUNHO",
      syncStatus: "SYNCED",
      versaoEntidade: 1,
      payload: {
        numeroRdo: "RDO-0042",
      },
      createdAt: "2026-07-22T10:00:00Z",
      updatedAt: "2026-07-22T10:01:00Z",
    });

    render(
      <RdoCreatePage
        initialDraft={draft}
        isExisting
        creationContext={context()}
        onBackToList={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    expect(screen.getByLabelText(/^Número do RDO/)).toHaveValue("RDO-STALE");
    fireEvent.click(
      screen.getByRole("button", { name: "Sincronizar agora" }),
    );

    await waitFor(() => {
      expect(mocks.synchronize).toHaveBeenCalledOnce();
      expect(screen.getByLabelText(/^Número do RDO/)).toHaveValue("RDO-0042");
    });
  });
});

afterEach(cleanup);

describe("catálogo contextual de mão de obra em RDO legado/importado", () => {
  it("mantém obra e número canônicos somente leitura no formulário", () => {
    const draft = legacyDraft();
    draft.numeroRdo = "RDO-1";

    render(
      <RdoCreatePage
        initialDraft={draft}
        isExisting={false}
        creationContext={context()}
        onBackToList={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    expect(screen.getByLabelText(/^Obra ID/)).toHaveValue("obra-a");
    expect(screen.getByLabelText(/^Obra ID/)).toHaveAttribute("readonly");
    expect(screen.getByLabelText(/^Número do RDO/)).toHaveValue("RDO-1");
    expect(screen.getByLabelText(/^Número do RDO/)).toHaveAttribute("readonly");
    expect(
      screen.getByText("Definido automaticamente pela obra selecionada."),
    ).toBeVisible();
    expect(
      screen.getByText("Gerado automaticamente pelo contexto canônico do RDO."),
    ).toBeVisible();
  });

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

  it("não reabre o hard stop canônico para rascunho LOCAL_PENDING já persistido", async () => {
    const draft = legacyDraft();
    draft.syncStatus = "LOCAL_PENDING";

    render(
      <RdoCreatePage
        initialDraft={draft}
        isExisting
        onBackToList={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    expect(
      await screen.findByText(
        "Contexto canônico pendente; o RDO permanece local até a validação automática.",
      ),
    ).toBeVisible();
    expect(mocks.requireContext).not.toHaveBeenCalled();
  });
});
