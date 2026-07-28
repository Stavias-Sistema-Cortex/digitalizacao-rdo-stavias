// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { LocalRdoRecord } from "../../lib/db/db.types";
import { RDO_IMPORT_LIMITS } from "../../lib/files/rdoImportResourcePolicy";
import { clearSession, setSession } from "../auth/authSession";
import { RdoLocalList } from "./RdoLocalList";
import { localRdoPdfExportAvailability } from "./export/rdoPdfAvailability";
import type { RdoExportDownloadPermit } from "./export/rdoExportDownload";

const mocks = vi.hoisted(() => ({
  listWorksites: vi.fn(),
  downloadLocal: vi.fn(),
  downloadServer: vi.fn(),
  downloadPdfLocal: vi.fn(),
  downloadPdfServer: vi.fn(),
  onWorkbookModuleLoad: vi.fn(),
}));

vi.mock("./rdoCreationContextRepository", () => ({
  listCachedAuthorizedRdoWorksites: mocks.listWorksites,
}));

vi.mock("./export/exportRdoWorkbook", () => {
  // This callback models a session rotation while the lazy module resolves.
  // It must run before the list invokes the downloaded exporter.
  mocks.onWorkbookModuleLoad();
  return {
    downloadRdoWorkbook: mocks.downloadLocal,
    downloadAuthoritativeRdoWorkbook: mocks.downloadServer,
  };
});

vi.mock("./export/exportRdoPdf", () => ({
  downloadRdoPdf: mocks.downloadPdfLocal,
  downloadAuthoritativeRdoPdf: mocks.downloadPdfServer,
}));

vi.mock("../programacoes/ProgramacaoSemanalImport", () => ({
  ProgramacaoSemanalImport: () => null,
}));

const OWNER_A = "00000000-0000-4000-8000-000000000010";
const OWNER_B = "00000000-0000-4000-8000-000000000011";
const WORKSITE_A = "00000000-0000-4000-8000-000000000020";
const WORKSITE_B = "00000000-0000-4000-8000-000000000021";
const EXPORT_STATE_TEST_ID = "rdo-export-state-rdo-local-1";

function session(
  ownerId = OWNER_A,
  options: { obraIds?: string[]; global?: boolean; nome?: string } = {},
) {
  const global = options.global ?? true;
  return {
    colaboradorId: ownerId,
    nome: options.nome ?? "Encarregado de teste",
    papelAcesso: global ? "ALFA" as const : "BETA" as const,
    escopoGlobal: global,
    obraIds: options.obraIds ?? [],
    expiraEm: "2099-07-14T12:00:00Z",
  };
}

function record(
  overrides: Partial<LocalRdoRecord> = {},
): LocalRdoRecord {
  return {
    id: "rdo-local-1",
    obraId: "obra-1",
    programacaoId: null,
    numeroRdo: "RDO-1",
    dataRdo: "2026-07-22",
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: null,
    payload: {
      cliente: "Cliente",
      contrato: "CTR-1",
      rodovia: "BR-101",
      cidade: "São Paulo",
      uf: "SP",
      previousRdoId: "",
      creationContextVersion: 1,
      apontadorColaboradorId: "",
      kmInicialProgramado: "",
      kmFinalProgramado: "",
      kmInicialInterditado: "",
      kmFinalInterditado: "",
      turno: "DIURNO",
      horaInicio: "",
      horaFim: "",
      condicaoManha: "",
      condicaoTarde: "",
      condicaoNoite: "",
      pluviometriaMm: "",
      observacoes: "",
      preenchidoPor: "",
      apontadorRdo: "",
      encarregadoObra: "",
      fiscalizacaoCampo: "",
      servicosExecutados: [],
      alocacoesColaboradores: [],
      maoObra: [],
      equipamentos: [],
      materiais: [],
      controlesGeometricos: [],
      attachments: [],
      importEvidence: null,
    },
    createdAt: "2026-07-22T10:00:00Z",
    updatedAt: "2026-07-22T10:00:00Z",
    ...overrides,
  };
}

function list(localRecord = record()) {
  return (
    <RdoLocalList
      records={[localRecord]}
      events={[]}
      attachments={[]}
      isLoading={false}
      error=""
      onCreate={vi.fn()}
      onImportRdoFile={vi.fn()}
      isImporting={false}
      onOpen={vi.fn()}
      onRefresh={vi.fn()}
    />
  );
}

function renderList(localRecord = record()) {
  return render(list(localRecord));
}

async function expectExportStateToContain(expected: string) {
  await waitFor(() => {
    expect(screen.getByTestId(EXPORT_STATE_TEST_ID)).toHaveTextContent(
      expected,
    );
  });
}

describe("RdoLocalList offline export", () => {
  afterEach(() => {
    cleanup();
    clearSession();
  });

  beforeEach(() => {
    clearSession();
    setSession(session());
    mocks.listWorksites.mockReset();
    mocks.downloadLocal.mockReset();
    mocks.downloadServer.mockReset();
    mocks.downloadPdfLocal.mockReset();
    mocks.downloadPdfServer.mockReset();
    mocks.onWorkbookModuleLoad.mockReset();
    Object.defineProperty(window.navigator, "onLine", {
      configurable: true,
      value: false,
    });
  });

  it("shares glyph rejection through the static PDF availability helper", () => {
    const availability = localRdoPdfExportAvailability(
      record({
        payload: {
          ...record().payload,
          observacoes: "Frente com alerta ⚠",
        },
      }),
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    );

    expect(availability).toEqual({
      ready: false,
      code: "RDO_EXPORT_UNSUPPORTED_PDF_GLYPH",
      message:
        "O conteúdo do RDO contém caractere sem representação segura no PDF; nenhum conteúdo foi substituído.",
    });
  });

  it("rejects an oversized import in the file input without replacing the rendered list", async () => {
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    const onImportRdoFile = vi.fn();
    const rendered = render(
      <RdoLocalList
        records={[record()]}
        events={[]}
        attachments={[]}
        isLoading={false}
        error=""
        onCreate={vi.fn()}
        onImportRdoFile={onImportRdoFile}
        isImporting={false}
        onOpen={vi.fn()}
        onRefresh={vi.fn()}
      />,
    );
    const input = rendered.container.querySelector<HTMLInputElement>(
      'input[type="file"][accept*=".pdf"]',
    );
    const oversized = new File(["rdo"], "RDO-grande.pdf", {
      type: "application/pdf",
    });
    Object.defineProperty(oversized, "size", {
      configurable: true,
      value: RDO_IMPORT_LIMITS.fileBytes + 1,
    });

    expect(input).not.toBeNull();
    fireEvent.change(input!, {
      target: {
        files: [oversized],
      },
    });

    expect(onImportRdoFile).not.toHaveBeenCalled();
    expect(await screen.findByText(
      /O arquivo de RDO excede o limite/,
    )).toBeInTheDocument();
    expect(screen.getByText("RDO-1")).toBeInTheDocument();
  });

  it("blocks a local XLSX download when the session changes while its lazy module resolves", async () => {
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    mocks.onWorkbookModuleLoad.mockImplementation(() => {
      setSession(session(OWNER_B));
    });
    renderList();

    const button = await screen.findByRole("button", { name: "Exportar XLSX" });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);

    await expectExportStateToContain(
      "XLSX: A sessão mudou durante a exportação local do RDO; o download foi bloqueado.",
    );
    expect(mocks.downloadLocal).not.toHaveBeenCalled();
  });

  it("blocks both local formats after an owner rotation leaves an offline RDO rendered", async () => {
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    renderList();

    const xlsxButton = await screen.findByRole("button", { name: "Exportar XLSX" });
    await waitFor(() => expect(xlsxButton).toBeEnabled());
    setSession(session(OWNER_B));

    fireEvent.click(xlsxButton);
    await expectExportStateToContain(
      "XLSX: A sessão mudou durante a exportação local do RDO; o download foi bloqueado.",
    );

    fireEvent.click(screen.getByRole("button", { name: "Exportar PDF" }));
    expect(await screen.findByTestId(
      EXPORT_STATE_TEST_ID,
    )).toHaveTextContent(
      "PDF: A sessão mudou durante a exportação local do RDO; o download foi bloqueado.",
    );
    expect(mocks.downloadLocal).not.toHaveBeenCalled();
    expect(mocks.downloadPdfLocal).not.toHaveBeenCalled();
  });

  it("blocks a stale worksite after the same BETA owner loses that worksite scope", async () => {
    setSession(session(OWNER_A, {
      global: false,
      obraIds: [WORKSITE_A],
    }));
    mocks.listWorksites.mockResolvedValue([
      { id: WORKSITE_A, nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    renderList(record({ obraId: WORKSITE_A }));

    const xlsxButton = await screen.findByRole("button", { name: "Exportar XLSX" });
    await waitFor(() => expect(xlsxButton).toBeEnabled());
    setSession(session(OWNER_A, {
      global: false,
      obraIds: [WORKSITE_B],
    }));

    fireEvent.click(xlsxButton);
    await expectExportStateToContain(
      "XLSX: A sessão mudou durante a exportação local do RDO; o download foi bloqueado.",
    );

    fireEvent.click(screen.getByRole("button", { name: "Exportar PDF" }));
    expect(await screen.findByTestId(
      EXPORT_STATE_TEST_ID,
    )).toHaveTextContent(
      "PDF: A sessão mudou durante a exportação local do RDO; o download foi bloqueado.",
    );
    expect(mocks.downloadLocal).not.toHaveBeenCalled();
    expect(mocks.downloadPdfLocal).not.toHaveBeenCalled();
  });

  it("keeps a same-scope export valid when only the display name refreshes", async () => {
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    renderList();

    const button = await screen.findByRole("button", { name: "Exportar XLSX" });
    await waitFor(() => expect(button).toBeEnabled());
    setSession(session(OWNER_A, { nome: "Nome operacional atualizado" }));
    fireEvent.click(button);

    await waitFor(() => expect(mocks.downloadLocal).toHaveBeenCalledOnce());
  });

  it("reasserts the session at the local downloader boundary", async () => {
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    mocks.downloadLocal.mockImplementation(async (
      _snapshot: unknown,
      permit: RdoExportDownloadPermit,
    ) => {
      setSession(session(OWNER_B));
      permit.assertCurrentAuthorization();
    });
    renderList();

    const button = await screen.findByRole("button", { name: "Exportar XLSX" });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);

    await expectExportStateToContain(
      "XLSX: A sessão mudou durante a exportação local do RDO; o download foi bloqueado.",
    );
    expect(mocks.downloadLocal).toHaveBeenCalledOnce();
  });

  it("enables a complete canonical snapshot and downloads it locally offline", async () => {
    mocks.listWorksites.mockResolvedValue([
      {
        id: "obra-1",
        nome: "Obra Norte",
        codigoContrato: "CTR-1",
      },
    ]);
    const view = renderList();

    const button = await screen.findByRole("button", { name: "Exportar XLSX" });
    await waitFor(() => expect(button).toBeEnabled());
    expect(screen.getByTestId(EXPORT_STATE_TEST_ID)).toHaveTextContent(
      "Origem da exportação · Dados locais pendentes · disponível offline",
    );
    expect(
      view.container.querySelectorAll(".rdo-export-state"),
    ).toHaveLength(1);

    fireEvent.click(button);

    await waitFor(() => expect(mocks.downloadLocal).toHaveBeenCalledOnce());
    expect(mocks.downloadServer).not.toHaveBeenCalled();
    const exportState = await screen.findByTestId(EXPORT_STATE_TEST_ID);
    expect(exportState).toHaveTextContent(
      "Origem da exportação · Dados locais pendentes · disponível offline",
    );
    expect(exportState).toHaveTextContent(
      "XLSX local gerado; o RDO ainda está pendente de sincronização.",
    );
    expect(exportState).toHaveTextContent("XLSX e PDF disponíveis");
  });

  it("uses the local snapshot online while the RDO is still pending", async () => {
    Object.defineProperty(window.navigator, "onLine", {
      configurable: true,
      value: true,
    });
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    renderList(record({ syncStatus: "PENDING_SYNC", versaoEntidade: null }));

    const button = await screen.findByRole("button", { name: "Exportar XLSX" });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);

    await waitFor(() => expect(mocks.downloadLocal).toHaveBeenCalledOnce());
    expect(mocks.downloadServer).not.toHaveBeenCalled();
  });

  it("downloads a complete pending RDO as PDF locally offline", async () => {
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    renderList();

    const button = await screen.findByRole("button", { name: "Exportar PDF" });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);

    await waitFor(() => expect(mocks.downloadPdfLocal).toHaveBeenCalledOnce());
    expect(mocks.downloadPdfServer).not.toHaveBeenCalled();
  });

  it("uses the authenticated server only for a synced server-versioned RDO", async () => {
    Object.defineProperty(window.navigator, "onLine", {
      configurable: true,
      value: true,
    });
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    renderList(record({ syncStatus: "SYNCED", versaoEntidade: 7 }));

    await expectExportStateToContain(
      "Origem da exportação · Servidor · cópia local disponível offline",
    );
    fireEvent.click(screen.getByRole("button", { name: "Exportar XLSX" }));

    await waitFor(() => expect(mocks.downloadServer).toHaveBeenCalledOnce());
    expect(mocks.downloadLocal).not.toHaveBeenCalled();
  });

  it("uses the authenticated server only for a synced server-versioned PDF", async () => {
    Object.defineProperty(window.navigator, "onLine", {
      configurable: true,
      value: true,
    });
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    renderList(record({ syncStatus: "SYNCED", versaoEntidade: 7 }));

    const button = await screen.findByRole("button", { name: "Exportar PDF" });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);

    await waitFor(() => expect(mocks.downloadPdfServer).toHaveBeenCalledOnce());
    expect(mocks.downloadPdfLocal).not.toHaveBeenCalled();
  });

  it("does not silently fall back to local export after a server rejection", async () => {
    Object.defineProperty(window.navigator, "onLine", {
      configurable: true,
      value: true,
    });
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    mocks.downloadServer.mockRejectedValue(
      new Error("O servidor recusou a exportação do RDO (403)."),
    );
    renderList(record({ syncStatus: "SYNCED", versaoEntidade: 7 }));

    const button = await screen.findByRole("button", { name: "Exportar XLSX" });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);

    await expectExportStateToContain(
      "XLSX: O servidor recusou a exportação do RDO (403).",
    );
    expect(mocks.downloadServer).toHaveBeenCalledOnce();
    expect(mocks.downloadLocal).not.toHaveBeenCalled();
  });

  it("does not silently fall back after a PDF server rejection", async () => {
    Object.defineProperty(window.navigator, "onLine", {
      configurable: true,
      value: true,
    });
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    mocks.downloadPdfServer.mockRejectedValue(
      new Error("O servidor recusou a exportação do RDO (403)."),
    );
    renderList(record({ syncStatus: "SYNCED", versaoEntidade: 7 }));

    const button = await screen.findByRole("button", { name: "Exportar PDF" });
    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);

    await expectExportStateToContain(
      "PDF: O servidor recusou a exportação do RDO (403).",
    );
    expect(mocks.downloadPdfServer).toHaveBeenCalledOnce();
    expect(mocks.downloadPdfLocal).not.toHaveBeenCalled();
    expect(mocks.downloadLocal).not.toHaveBeenCalled();
    expect(mocks.downloadServer).not.toHaveBeenCalled();
  });

  it("keeps XLSX available when a PDF glyph cannot be represented safely", async () => {
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    renderList(record({
      payload: {
        ...record().payload,
        observacoes: "Frente com alerta ⚠",
      },
    }));

    const xlsxButton = await screen.findByRole("button", { name: "Exportar XLSX" });
    const pdfButton = screen.getByRole("button", { name: "Exportar PDF" });
    await waitFor(() => expect(xlsxButton).toBeEnabled());
    expect(pdfButton).toBeDisabled();
    const exportState = await screen.findByTestId(EXPORT_STATE_TEST_ID);
    expect(exportState).toHaveTextContent(
      "Origem da exportação · Dados locais pendentes · disponível offline",
    );
    expect(exportState).toHaveTextContent("XLSX disponível");
    expect(exportState).toHaveTextContent(
      "PDF indisponível: O conteúdo do RDO contém caractere sem representação segura no PDF; nenhum conteúdo foi substituído.",
    );
    expect(exportState).not.toHaveAttribute("aria-label");
    expect(xlsxButton).toHaveAttribute("aria-describedby", exportState.id);
    expect(pdfButton).toHaveAttribute("aria-describedby", exportState.id);
    expect(xlsxButton).toHaveAccessibleDescription(
      /disponível offline · XLSX disponível · PDF indisponível:/,
    );
    expect(pdfButton).toHaveAccessibleDescription(
      /disponível offline · XLSX disponível · PDF indisponível:/,
    );
  });

  it("validates PDF list availability without loading the PDF export module", async () => {
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    renderList();

    const pdfButton = await screen.findByRole("button", { name: "Exportar PDF" });
    await waitFor(() => expect(pdfButton).toBeEnabled());
  });

  it("does not reuse PDF availability when a record payload changes without metadata", async () => {
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    const view = renderList();

    const pdfButton = await screen.findByRole("button", { name: "Exportar PDF" });
    await waitFor(() => expect(pdfButton).toBeEnabled());

    view.rerender(list(record({
      payload: {
        ...record().payload,
        observacoes: "Frente com alerta ⚠",
      },
    })));

    expect(pdfButton).toBeDisabled();
    expect(screen.getByTestId(EXPORT_STATE_TEST_ID)).toHaveTextContent(
      "PDF indisponível: O conteúdo do RDO contém caractere sem representação segura no PDF; nenhum conteúdo foi substituído.",
    );
    expect(pdfButton).toBeDisabled();
  });

  it("preserves each format notice after independent export failures", async () => {
    Object.defineProperty(window.navigator, "onLine", {
      configurable: true,
      value: true,
    });
    mocks.listWorksites.mockResolvedValue([
      { id: "obra-1", nome: "Obra Norte", codigoContrato: "CTR-1" },
    ]);
    mocks.downloadServer.mockRejectedValue(new Error("Falha XLSX."));
    mocks.downloadPdfServer.mockRejectedValue(new Error("Falha PDF."));
    renderList(record({ syncStatus: "SYNCED", versaoEntidade: 7 }));

    const xlsxButton = await screen.findByRole("button", { name: "Exportar XLSX" });
    const pdfButton = screen.getByRole("button", { name: "Exportar PDF" });
    await waitFor(() => expect(pdfButton).toBeEnabled());
    fireEvent.click(xlsxButton);
    await expectExportStateToContain("XLSX: Falha XLSX.");

    fireEvent.click(pdfButton);
    const exportState = await screen.findByTestId(EXPORT_STATE_TEST_ID);
    expect(exportState).toHaveTextContent("XLSX: Falha XLSX.");
    expect(exportState).toHaveTextContent("PDF: Falha PDF.");
    expect(exportState).toHaveTextContent(
      "Origem da exportação · Servidor · cópia local disponível offline",
    );
    expect(exportState).toHaveTextContent("XLSX e PDF disponíveis");
    expect(document.querySelectorAll(".rdo-export-state")).toHaveLength(1);
  });

  it("keeps export disabled with the literal missing-worksite reason", async () => {
    mocks.listWorksites.mockResolvedValue([]);
    renderList();

    const button = await screen.findByRole("button", { name: "Exportar XLSX" });
    const pdfButton = screen.getByRole("button", { name: "Exportar PDF" });
    await waitFor(() => expect(button).toBeDisabled());
    expect(pdfButton).toBeDisabled();
    expect(screen.getByTestId(EXPORT_STATE_TEST_ID)).toHaveTextContent(
      "XLSX e PDF indisponíveis: A obra canônica deste RDO não está completa no armazenamento offline.",
    );
    expect(mocks.downloadLocal).not.toHaveBeenCalled();
  });
});
