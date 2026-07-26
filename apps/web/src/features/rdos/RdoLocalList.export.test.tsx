// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { LocalRdoRecord } from "../../lib/db/db.types";
import { RdoLocalList } from "./RdoLocalList";

const mocks = vi.hoisted(() => ({
  listWorksites: vi.fn(),
  downloadLocal: vi.fn(),
  downloadServer: vi.fn(),
  downloadPdfLocal: vi.fn(),
  downloadPdfServer: vi.fn(),
}));

vi.mock("./rdoCreationContextRepository", () => ({
  listCachedAuthorizedRdoWorksites: mocks.listWorksites,
}));

vi.mock("./export/exportRdoWorkbook", () => ({
  downloadRdoWorkbook: mocks.downloadLocal,
  downloadAuthoritativeRdoWorkbook: mocks.downloadServer,
}));

vi.mock("./export/exportRdoPdf", async (importOriginal) => ({
  ...await importOriginal<typeof import("./export/exportRdoPdf")>(),
  downloadRdoPdf: mocks.downloadPdfLocal,
  downloadAuthoritativeRdoPdf: mocks.downloadPdfServer,
}));

vi.mock("../programacoes/ProgramacaoSemanalImport", () => ({
  ProgramacaoSemanalImport: () => null,
}));

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

function renderList(localRecord = record()) {
  render(
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
    />,
  );
}

describe("RdoLocalList offline export", () => {
  afterEach(() => cleanup());

  beforeEach(() => {
    mocks.listWorksites.mockReset();
    mocks.downloadLocal.mockReset();
    mocks.downloadServer.mockReset();
    mocks.downloadPdfLocal.mockReset();
    mocks.downloadPdfServer.mockReset();
    Object.defineProperty(window.navigator, "onLine", {
      configurable: true,
      value: false,
    });
  });

  it("enables a complete canonical snapshot and downloads it locally offline", async () => {
    mocks.listWorksites.mockResolvedValue([
      {
        id: "obra-1",
        nome: "Obra Norte",
        codigoContrato: "CTR-1",
      },
    ]);
    renderList();

    const button = await screen.findByRole("button", { name: "Exportar XLSX" });
    await waitFor(() => expect(button).toBeEnabled());
    expect(screen.getByText(
      "XLSX · Dados locais pendentes · exportação offline",
    )).toBeInTheDocument();

    fireEvent.click(button);

    await waitFor(() => expect(mocks.downloadLocal).toHaveBeenCalledOnce());
    expect(mocks.downloadServer).not.toHaveBeenCalled();
    expect(await screen.findByText(
      "XLSX local gerado; o RDO ainda está pendente de sincronização.",
    )).toBeInTheDocument();
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

    expect(await screen.findByText(
      "XLSX · Servidor autoritativo · cópia local pronta offline",
    )).toBeInTheDocument();
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

    expect(await screen.findByText(
      "XLSX: O servidor recusou a exportação do RDO (403).",
    )).toBeInTheDocument();
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

    expect(await screen.findByText(
      "PDF: O servidor recusou a exportação do RDO (403).",
    )).toBeInTheDocument();
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
    expect(await screen.findByText(
      "O conteúdo do RDO contém caractere sem representação segura no PDF; nenhum conteúdo foi substituído.",
    )).toBeInTheDocument();
  });

  it("keeps export disabled with the literal missing-worksite reason", async () => {
    mocks.listWorksites.mockResolvedValue([]);
    renderList();

    const button = await screen.findByRole("button", { name: "Exportar XLSX" });
    const pdfButton = screen.getByRole("button", { name: "Exportar PDF" });
    await waitFor(() => expect(button).toBeDisabled());
    expect(pdfButton).toBeDisabled();
    expect(screen.getAllByText(
      "A obra canônica deste RDO não está completa no armazenamento offline.",
    )).toHaveLength(2);
    expect(mocks.downloadLocal).not.toHaveBeenCalled();
  });
});
