// @vitest-environment jsdom

import { createRef } from "react";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { RdoCreationDialog } from "./RdoCreationDialog";
import { createEmptyRdo } from "./createEmptyRdo";
import { RDO_CONTEXT_OFFLINE_MISSING } from "./rdoCreationContext";
import type { RdoCreationContextLookup } from "./rdoLookupApi";
import { AUTH_SESSION_CHANGED_EVENT } from "../auth/authSession";

const mocks = vi.hoisted(() => ({
  listCached: vi.fn(),
  refresh: vi.fn(),
  getCached: vi.fn(),
  requireContext: vi.fn(),
  createDraft: vi.fn(),
}));

vi.mock("./rdoCreationContextRepository", () => ({
  listCachedAuthorizedRdoWorksites: mocks.listCached,
  refreshAuthorizedRdoWorksites: mocks.refresh,
  getCachedRdoCreationContext: mocks.getCached,
  requireRdoCreationContext: mocks.requireContext,
}));
vi.mock("./rdoDraftCreation", () => ({
  createAndPersistRdoDraft: mocks.createDraft,
}));

const WORKSITE_ID = "00000000-0000-4000-8000-000000000001";
const worksite = {
  id: WORKSITE_ID,
  codigoContrato: "CTR-041",
  nome: "Conservação de pavimento com um nome operacional longo",
  cliente: null,
  cidade: "Campinas",
  uf: "SP",
  rodovia: "SP-041",
  status: "ATIVA",
  observacoes: null,
  latitude: null,
  longitude: null,
  valorContratual: null,
  updatedAt: "2026-07-22T12:00:00.000Z",
};

function complete(total = 0) {
  return { status: "COMPLETE", total, returned: total, complete: true };
}

function context(partial = false): RdoCreationContextLookup {
  return {
    obra: {
      id: WORKSITE_ID,
      codigoContrato: "CTR-041",
      codigoCw: "CW-041",
      nome: worksite.nome,
      cliente: null,
      cidade: "Campinas",
      uf: "SP",
      rodovia: "SP-041",
      status: "ATIVA",
      version: 4,
    },
    data: "2026-07-22",
    nextNumberSuggestion: "RDO-0021",
    previousRdo: {
      id: "00000000-0000-4000-8000-000000000009",
      numeroRdo: "RDO-0020",
      dataRdo: "2026-07-21",
      status: "ENVIADO",
      version: 3,
    },
    previousWorkforce: [],
    programacoes: [],
    colaboradores: [],
    equipamentos: [],
    serviceCatalog: [],
    coverage: {
      previousWorkforce: complete(),
      programacoes: complete(),
      colaboradores: partial
        ? { status: "PARTIAL", total: 4, returned: 2, complete: false }
        : complete(),
      equipamentos: complete(),
      serviceCatalog: { status: "NOT_CONFIGURED", total: 0, returned: 0, complete: false },
      priceCatalog: { status: "NOT_CONFIGURED", total: 0, returned: 0, complete: false },
    },
    freshness: {
      status: "FRESH",
      sourceVersion: 5,
      generatedAt: "2026-07-22T12:00:00.000Z",
      staleAfter: "2099-07-22T12:15:00.000Z",
    },
    provenance: {
      receiptVersion: 7,
      sourceVersion: 5,
      worksiteId: WORKSITE_ID,
      selectedDate: "2026-07-22",
      previousRdoId: "00000000-0000-4000-8000-000000000009",
      generatedAt: "2026-07-22T12:00:00.000Z",
    },
  };
}

function setOnline(value: boolean) {
  Object.defineProperty(window.navigator, "onLine", {
    configurable: true,
    value,
  });
}

beforeEach(() => {
  mocks.listCached.mockResolvedValue([worksite]);
  mocks.refresh.mockResolvedValue([worksite]);
  mocks.requireContext.mockResolvedValue({
    source: "CACHE",
    cachedAt: "2026-07-22T12:01:00.000Z",
    context: context(),
  });
  mocks.getCached.mockResolvedValue({
    ownerId: "user",
    obraId: WORKSITE_ID,
    selectedDate: "2026-07-22",
    sourceVersion: 5,
    receiptVersion: 7,
    cachedAt: "2026-07-22T12:01:00.000Z",
    coverage: context().coverage,
    context: context(),
  });
  const draft = createEmptyRdo();
  draft.id = "00000000-0000-4000-8000-000000000030";
  draft.obraId = WORKSITE_ID;
  draft.dataRdo = "2026-07-22";
  draft.syncStatus = "PENDING_SYNC";
  mocks.createDraft.mockResolvedValue({ draft, mutation: {} });
  setOnline(false);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function renderDialog(overrides: {
  onClose?: () => void;
  onCreated?: (draft: ReturnType<typeof createEmptyRdo>, context: RdoCreationContextLookup) => void;
} = {}) {
  const returnFocusRef = createRef<HTMLButtonElement>();
  const onClose = overrides.onClose ?? vi.fn();
  const onCreated = overrides.onCreated ?? vi.fn();
  render(
    <>
      <button ref={returnFocusRef}>Origem</button>
      <RdoCreationDialog
        returnFocusRef={returnFocusRef}
        onClose={onClose}
        onCreated={onCreated}
      />
    </>,
  );
  return { returnFocusRef, onClose, onCreated };
}

describe("diálogo obra-first de RDO", () => {
  it("carrega cache antes da rede e inicia o foco na busca", async () => {
    setOnline(true);
    let releaseRefresh!: (value: typeof worksite[]) => void;
    mocks.refresh.mockReturnValue(
      new Promise((resolve) => {
        releaseRefresh = resolve;
      }),
    );
    renderDialog();

    expect(await screen.findByRole("radio", { name: /Conservação de pavimento/i }))
      .toBeVisible();
    expect(screen.getByRole("searchbox", { name: "Buscar obra" })).toHaveFocus();
    expect(mocks.listCached).toHaveBeenCalledTimes(1);
    releaseRefresh([worksite]);
    await waitFor(() => expect(mocks.refresh).toHaveBeenCalledTimes(1));
  });

  it("exige obra e data, mostra proveniência persistida e só então cria", async () => {
    const user = userEvent.setup();
    const { onCreated } = renderDialog();
    const create = screen.getByRole("button", { name: "Criar rascunho" });
    expect(create).toBeDisabled();

    await user.click(await screen.findByRole("radio", { name: /Conservação/i }));
    expect(create).toBeDisabled();
    await user.type(screen.getByLabelText("Data do RDO"), "2026-07-22");

    expect(await screen.findByText("Cache local")).toBeVisible();
    expect(screen.getByText("Versão 5")).toBeVisible();
    expect(screen.getByText("RDO-0020")).toBeVisible();
    expect(screen.getByText("Atualizado")).toBeVisible();
    expect(create).toBeEnabled();
    await user.click(create);

    await waitFor(() => expect(mocks.createDraft).toHaveBeenCalledWith(context()));
    expect(onCreated).toHaveBeenCalledWith(
      expect.objectContaining({ obraId: WORKSITE_ID }),
      context(),
    );
  });

  it("expõe cobertura parcial literalmente e não habilita a transação", async () => {
    const user = userEvent.setup();
    mocks.requireContext.mockResolvedValue({
      source: "CACHE",
      cachedAt: "2026-07-22T12:01:00.000Z",
      context: context(true),
    });
    mocks.getCached.mockResolvedValue({
      ownerId: "user",
      obraId: WORKSITE_ID,
      selectedDate: "2026-07-22",
      sourceVersion: 5,
      receiptVersion: 7,
      cachedAt: "2026-07-22T12:01:00.000Z",
      coverage: context(true).coverage,
      context: context(true),
    });
    renderDialog();
    await user.click(await screen.findByRole("radio", { name: /Conservação/i }));
    await user.type(screen.getByLabelText("Data do RDO"), "2026-07-22");

    expect(await screen.findByText("Parcial")).toBeVisible();
    expect(screen.getByText("Colaboradores 2/4")).toBeVisible();
    expect(screen.getByRole("button", { name: "Criar rascunho" })).toBeDisabled();
  });

  it("mostra a cópia offline exata quando não há contexto", async () => {
    const user = userEvent.setup();
    mocks.requireContext.mockRejectedValue(new Error(RDO_CONTEXT_OFFLINE_MISSING));
    mocks.getCached.mockResolvedValue(undefined);
    renderDialog();
    await user.click(await screen.findByRole("radio", { name: /Conservação/i }));
    await user.type(screen.getByLabelText("Data do RDO"), "2026-07-22");
    expect(await screen.findByText(RDO_CONTEXT_OFFLINE_MISSING)).toBeVisible();
  });

  it("prende Tab, fecha com Escape e devolve o foco ao botão Novo RDO", async () => {
    const user = userEvent.setup();
    const { onClose, returnFocusRef } = renderDialog();
    const dialog = screen.getByRole("dialog", { name: "Criar RDO" });
    const close = screen.getByRole("button", { name: "Fechar" });
    close.focus();
    await user.tab();
    expect(dialog.contains(document.activeElement)).toBe(true);
    await user.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(returnFocusRef.current).toHaveFocus();
  });

  it("fecha e descarta contexto em memória quando a sessão muda", async () => {
    const user = userEvent.setup();
    const { onClose } = renderDialog();
    await user.click(await screen.findByRole("radio", { name: /Conservação/i }));
    await user.type(screen.getByLabelText("Data do RDO"), "2026-07-22");
    expect(await screen.findByText("Cache local")).toBeVisible();
    expect(screen.getByRole("button", { name: "Criar rascunho" })).toBeEnabled();

    window.dispatchEvent(new Event(AUTH_SESSION_CHANGED_EVENT));

    expect(onClose).toHaveBeenCalledTimes(1);
    await waitFor(() => {
      expect(screen.queryByText("Cache local")).not.toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: "Criar rascunho" }),
      ).toBeDisabled();
    });
    expect(mocks.createDraft).not.toHaveBeenCalled();
  });
});
