// @vitest-environment jsdom

import type { ReactNode } from "react";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  createEmptyAlocacaoColaborador,
  createEmptyMaoObra,
  createEmptyRdo,
} from "./createEmptyRdo";
import type { RdoCreationContextLookup } from "./rdoLookupApi";
import type { RdoDraft } from "./rdo.types";
import { SYNC_COMPLETED_EVENT } from "../../lib/sync/syncEvents";

const mocks = vi.hoisted(() => ({
  listRdoAttachments: vi.fn(),
  getCachedContext: vi.fn(),
  requireContext: vi.fn(),
  getLocalRdo: vi.fn(),
  synchronize: vi.fn(),
  buscarColaboradores: vi.fn(),
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

vi.mock("./rdoLookupApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./rdoLookupApi")>();
  return {
    ...actual,
    buscarColaboradores: mocks.buscarColaboradores,
  };
});

vi.mock("./RdoWorkforceEditor", () => ({
  RdoWorkforceEditor: ({
    draft,
    collaborators,
    catalogUnavailableMessage,
    onChange,
  }: {
    draft: RdoDraft;
    collaborators: Array<{ nome: string }>;
    catalogUnavailableMessage?: string;
    onChange: (draft: RdoDraft) => void;
  }) => (
    <section aria-label="Equipe contextual">
      {catalogUnavailableMessage ??
        collaborators.map((item) => item.nome).join(", ")}
      <div aria-label="Equipe atual">
        {draft.maoObra.map((item) => (
          <output key={item.localId} data-testid={`worker-${item.localId}`}>
            {[
              item.colaboradorId,
              item.nomeColaborador,
              item.origin,
              item.availability,
            ].join("|")}
          </output>
        ))}
      </div>
      <output aria-label="Rateios atuais">
        {draft.alocacoesColaboradores.length}
      </output>
      <output aria-label="Apontador atual">
        {draft.apontadorColaboradorId}
      </output>
      <button
        type="button"
        onClick={() =>
          onChange({
            ...draft,
            maoObra: [
              ...draft.maoObra,
              {
                ...createEmptyMaoObra(),
                localId: "concurrent-worker",
                colaboradorId: "worker-a",
                nomeColaborador: "Ana concorrente",
                origin: "AUTHORIZED_CONTEXT",
                availability: "AVAILABLE",
                selected: true,
              },
            ],
          })
        }
      >
        Adicionar durante reidratação
      </button>
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

function draftWithRejectedWorkforce(): RdoDraft {
  const draft = legacyDraft();
  draft.syncStatus = "PENDING_SYNC";
  draft.apontadorColaboradorId = "foreign-worker";
  draft.apontadorRdo = "Pessoa preservada por nome";
  draft.maoObra = [{
    ...createEmptyMaoObra(),
    localId: "legacy-worker",
    origemItemId: "source-item",
    sourceRdoId: "source-rdo",
    origin: "PREVIOUS_RDO",
    availability: "AVAILABLE",
    selected: true,
    colaboradorId: "foreign-worker",
    nomeColaborador: "Pessoa preservada por nome",
    cargo: "Servente",
  }];
  draft.alocacoesColaboradores = [{
    ...createEmptyAlocacaoColaborador(),
    localId: "legacy-allocation",
    colaboradorId: "foreign-worker",
  }];
  return draft;
}

function repairedLocalRecord(draft: RdoDraft) {
  return {
    id: draft.id,
    obraId: draft.obraId,
    programacaoId: null,
    numeroRdo: "RDO-0042",
    dataRdo: draft.dataRdo,
    statusRdo: "RASCUNHO" as const,
    syncStatus: "PENDING_SYNC" as const,
    versaoEntidade: 7,
    payload: {
      apontadorColaboradorId: null,
      apontadorRdo: "Pessoa preservada por nome",
      alocacoesColaboradores: [],
      maoObra: [{
        id: "legacy-worker",
        origemItemId: null,
        sourceRdoId: null,
        origin: "MANUAL",
        availability: "UNKNOWN",
        selected: true,
        colaboradorId: null,
        nomeColaborador: "Pessoa preservada por nome",
        cargo: "Servente",
        tipoVinculo: "OUTRO",
        quantidade: 1,
        horaInicio: null,
        horaFim: null,
        observacoes: null,
      }],
    },
    createdAt: "2026-07-22T10:00:00.000Z",
    updatedAt: "2026-07-22T10:02:00.000Z",
  };
}

beforeEach(() => {
  mocks.listRdoAttachments.mockResolvedValue([]);
  mocks.getCachedContext.mockResolvedValue(undefined);
  mocks.requireContext.mockReset();
  mocks.getLocalRdo.mockReset();
  mocks.synchronize.mockReset();
  mocks.synchronize.mockResolvedValue(undefined);
  mocks.buscarColaboradores.mockReset();
  mocks.buscarColaboradores.mockResolvedValue([
    {
      id: "foreign-worker",
      codigoColaborador: "999",
      cpfMascarado: null,
      nome: "Pessoa fora da equipe",
      email: null,
      nomeGrupo: null,
      nomePerfil: "Operador",
      ativo: true,
      atualizadoEm: null,
    },
  ]);
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

  it("reconciles the visible RDO after automatic synchronization", async () => {
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
      versaoEntidade: 2,
      payload: {
        numeroRdo: "RDO-0042",
      },
      createdAt: "2026-07-22T10:00:00Z",
      updatedAt: "2026-07-22T10:02:00Z",
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

    await act(async () => {
      window.dispatchEvent(new Event(SYNC_COMPLETED_EVENT));
    });

    await waitFor(() => {
      expect(mocks.getLocalRdo).toHaveBeenCalledWith(draft.id);
      expect(screen.getByLabelText(/^Número do RDO/)).toHaveValue("RDO-0042");
      expect(screen.getByText("Confirmada")).toBeVisible();
    });
  });

  it("rehydrates the repaired workforce after automatic synchronization", async () => {
    const draft = draftWithRejectedWorkforce();
    mocks.getLocalRdo.mockResolvedValue(repairedLocalRecord(draft));

    render(
      <RdoCreatePage
        initialDraft={draft}
        isExisting
        creationContext={context()}
        onBackToList={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    expect(screen.getByTestId("worker-legacy-worker")).toHaveTextContent(
      "foreign-worker|Pessoa preservada por nome|PREVIOUS_RDO|AVAILABLE",
    );
    await act(async () => {
      window.dispatchEvent(new Event(SYNC_COMPLETED_EVENT));
    });

    await waitFor(() => {
      expect(screen.getByTestId("worker-legacy-worker")).toHaveTextContent(
        "|Pessoa preservada por nome|MANUAL|UNKNOWN",
      );
      expect(screen.getByLabelText("Rateios atuais")).toHaveTextContent("0");
      expect(screen.getByLabelText("Apontador atual")).toBeEmptyDOMElement();
    });
  });

  it("preserves a concurrent workforce edit while applying the persisted repair", async () => {
    const draft = draftWithRejectedWorkforce();
    let resolvePersisted!: (
      value: ReturnType<typeof repairedLocalRecord>,
    ) => void;
    mocks.getLocalRdo.mockReturnValue(
      new Promise((resolve) => {
        resolvePersisted = resolve;
      }),
    );

    render(
      <RdoCreatePage
        initialDraft={draft}
        isExisting
        creationContext={context()}
        onBackToList={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    window.dispatchEvent(new Event(SYNC_COMPLETED_EVENT));
    await waitFor(() => {
      expect(mocks.getLocalRdo).toHaveBeenCalledWith(draft.id);
    });
    fireEvent.click(
      screen.getByRole("button", {
        name: "Adicionar durante reidratação",
      }),
    );
    await act(async () => {
      resolvePersisted(repairedLocalRecord(draft));
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(screen.getByTestId("worker-legacy-worker")).toHaveTextContent(
        "|Pessoa preservada por nome|MANUAL|UNKNOWN",
      );
      expect(screen.getByTestId("worker-concurrent-worker")).toHaveTextContent(
        "worker-a|Ana concorrente|AUTHORIZED_CONTEXT|AVAILABLE",
      );
    });
  });

  it("loads the worksite context after a LOCAL_PENDING RDO synchronizes", async () => {
    const draft = legacyDraft();
    draft.syncStatus = "LOCAL_PENDING";
    mocks.getLocalRdo.mockResolvedValue({
      id: draft.id,
      obraId: draft.obraId,
      programacaoId: null,
      numeroRdo: "RDO-0042",
      dataRdo: draft.dataRdo,
      statusRdo: "RASCUNHO",
      syncStatus: "SYNCED",
      versaoEntidade: 1,
      payload: { numeroRdo: "RDO-0042" },
      createdAt: "2026-07-22T10:00:00Z",
      updatedAt: "2026-07-22T10:02:00Z",
    });
    mocks.requireContext.mockResolvedValue({
      source: "SERVER",
      cachedAt: "2026-07-22T10:02:00Z",
      context: context(),
    });

    render(
      <RdoCreatePage
        initialDraft={draft}
        isExisting
        onBackToList={vi.fn()}
        onSaved={vi.fn()}
      />,
    );
    expect(
      screen.getByText(
        "Contexto canônico pendente; o RDO permanece local até a validação automática.",
      ),
    ).toBeVisible();

    await act(async () => {
      window.dispatchEvent(new Event(SYNC_COMPLETED_EVENT));
    });

    await waitFor(() => {
      expect(mocks.requireContext).toHaveBeenCalledWith(
        "obra-a",
        "2026-07-22",
        true,
      );
      expect(screen.getByText("Ana da Obra A")).toBeVisible();
    });
  });
});

afterEach(cleanup);

describe("catálogo contextual de mão de obra em RDO legado/importado", () => {
  it("keeps actionable field and file guidance without generic section write-ups", () => {
    render(
      <RdoCreatePage
        initialDraft={legacyDraft()}
        isExisting={false}
        creationContext={context()}
        onBackToList={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    expect(
      screen.getByText(
        "Adicione até 5 fotos deste RDO.",
      ),
    ).toBeVisible();
    expect(
      screen.queryByText(/Registro operacional editável sem conexão/),
    ).toBeNull();
    expect(
      screen.queryByText("Dados que vinculam o RDO à obra e à programação."),
    ).toBeNull();
    expect(
      screen.queryByText("Equipamentos utilizados durante a execução."),
    ).toBeNull();
  });

  it("limita o rateio aos colaboradores canônicos selecionados na equipe do RDO", async () => {
    const draft = legacyDraft();
    draft.maoObra = [
      {
        localId: "workforce-a",
        origemItemId: "",
        sourceRdoId: "",
        origin: "AUTHORIZED_CONTEXT",
        availability: "AVAILABLE",
        selected: true,
        colaboradorId: "worker-a",
        nomeColaborador: "Ana da Obra A",
        cargo: "Operadora",
        tipoVinculo: "PROPRIO",
        quantidade: 1,
        horaInicio: "",
        horaFim: "",
        observacoes: "",
      },
      {
        localId: "workforce-b",
        origemItemId: "",
        sourceRdoId: "",
        origin: "AUTHORIZED_CONTEXT",
        availability: "AVAILABLE",
        selected: true,
        colaboradorId: "worker-b",
        nomeColaborador: "Bruno da Obra A",
        cargo: "Servente",
        tipoVinculo: "PROPRIO",
        quantidade: 1,
        horaInicio: "",
        horaFim: "",
        observacoes: "",
      },
      {
        localId: "workforce-stale",
        origemItemId: "",
        sourceRdoId: "",
        origin: "AUTHORIZED_CONTEXT",
        availability: "AVAILABLE",
        selected: true,
        colaboradorId: "worker-stale",
        nomeColaborador: "Pessoa com vínculo revogado",
        cargo: "Operadora",
        tipoVinculo: "PROPRIO",
        quantidade: 1,
        horaInicio: "",
        horaFim: "",
        observacoes: "",
      },
      {
        localId: "workforce-unknown",
        origemItemId: "",
        sourceRdoId: "",
        origin: "AUTHORIZED_CONTEXT",
        availability: "UNKNOWN",
        selected: true,
        colaboradorId: "worker-unknown",
        nomeColaborador: "Pessoa sem confirmação",
        cargo: "Operadora",
        tipoVinculo: "PROPRIO",
        quantidade: 1,
        horaInicio: "",
        horaFim: "",
        observacoes: "",
      },
    ];
    const activeContext = context();
    activeContext.colaboradores.push({
      id: "worker-b",
      codigoColaborador: "004",
      nome: "Bruno da Obra A",
      papelNaObra: "OPERACIONAL",
      nomePerfil: "Servente",
    });
    activeContext.colaboradores.push({
      id: "worker-unknown",
      codigoColaborador: "002",
      nome: "Pessoa sem confirmação",
      papelNaObra: "OPERACIONAL",
      nomePerfil: "Operadora",
    });

    render(
      <RdoCreatePage
        initialDraft={draft}
        isExisting={false}
        creationContext={activeContext}
        onBackToList={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    const section = screen
      .getByRole("heading", { name: "Rateio de colaboradores" })
      .closest("section");
    expect(section).not.toBeNull();
    fireEvent.click(
      within(section!).getByRole("button", { name: "+ Adicionar" }),
    );
    const collaboratorSearch = within(section!).getByRole("combobox", {
      name: "Colaborador",
    });
    fireEvent.focus(collaboratorSearch);

    expect(
      await within(section!).findByRole("option", {
        name: /Ana da Obra A.*Operadora/,
      }),
    ).toBeVisible();
    expect(
      within(section!).queryByRole("option", { name: /Pessoa fora da equipe/ }),
    ).not.toBeInTheDocument();
    expect(
      within(section!).queryByRole("option", {
        name: /Pessoa com vínculo revogado/,
      }),
    ).not.toBeInTheDocument();
    expect(
      within(section!).queryByRole("option", {
        name: /Pessoa sem confirmação/,
      }),
    ).not.toBeInTheDocument();
    expect(mocks.buscarColaboradores).not.toHaveBeenCalled();

    fireEvent.keyDown(collaboratorSearch, { key: "ArrowDown" });
    fireEvent.keyDown(collaboratorSearch, { key: "Enter" });
    await waitFor(() => {
      expect(collaboratorSearch).toHaveValue("Ana da Obra A");
    });
  });

  /*
   * O identificador da obra saiu da tela. Ele continua no rascunho e na
   * ontologia, que é onde tem função; na Identificação ele só competia com o
   * nome da obra pela atenção de quem aponta, e um UUID não diz a ninguém em
   * que obra está trabalhando.
   */
  it("não expõe o id da obra e mantém o número canônico somente leitura", () => {
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

    expect(screen.queryByLabelText(/^Obra ID/)).toBeNull();
    expect(screen.queryByLabelText(/^Programação ID/)).toBeNull();
    expect(screen.getByLabelText(/^Número do RDO/)).toHaveValue("RDO-1");
    expect(screen.getByLabelText(/^Número do RDO/)).toHaveAttribute("readonly");
    expect(
      screen.getByText("O número é preenchido automaticamente."),
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
