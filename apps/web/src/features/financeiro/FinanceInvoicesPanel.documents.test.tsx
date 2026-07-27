// @vitest-environment jsdom

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  arquivarNotaFiscal: vi.fn(),
  buscarConteudoDocumento: vi.fn(),
  buscarDocumentosNota: vi.fn(),
  salvarNotaFiscal: vi.fn(),
  vincularDocumentoNota: vi.fn(),
  createObjectUrl: vi.fn(),
  revokeObjectUrl: vi.fn(),
}));

vi.mock("./financeiroApi", () => ({
  arquivarNotaFiscal: mocks.arquivarNotaFiscal,
  buscarConteudoDocumento: mocks.buscarConteudoDocumento,
  buscarDocumentosNota: mocks.buscarDocumentosNota,
  salvarNotaFiscal: mocks.salvarNotaFiscal,
  vincularDocumentoNota: mocks.vincularDocumentoNota,
}));

vi.mock("./FiscalDocumentIntake", () => ({
  FiscalDocumentIntake: () => null,
}));

import { FinanceInvoicesPanel } from "./FinanceInvoicesPanel";
import type {
  FinanceInvoice,
  FinanceInvoiceDocument,
} from "./financeiro.types";

const INVOICE = {
  id: "invoice-1",
  obraId: "obra-1",
  fornecedorNome: "Fornecedor",
  numero: "NF-001",
  valorLiquido: 1200,
  moeda: "BRL",
  vencimentoEm: "2026-07-31",
  ocrStatus: "NAO_CONFIGURADO",
} as FinanceInvoice;

const DOCUMENT = {
  id: "document-1",
  notaFiscalId: INVOICE.id,
  tipoDocumento: "PDF",
  principal: true,
  nomeOriginal: "nota.pdf",
  mediaType: "application/pdf",
  tamanhoBytes: 512,
  enviadoPor: "Colaborador",
  confirmadoPor: "Financeiro",
  inspecaoStatus: "APROVADO",
} as FinanceInvoiceDocument;

function renderPanel() {
  return render(
    <FinanceInvoicesPanel
      obraId={INVOICE.obraId}
      invoices={[INVOICE]}
      suppliers={[]}
      costCenters={[]}
      categories={[]}
      statuses={[]}
      permissions={[]}
      onChanged={vi.fn()}
    />,
  );
}

beforeEach(() => {
  mocks.buscarDocumentosNota.mockResolvedValue([DOCUMENT]);
  mocks.buscarConteudoDocumento.mockResolvedValue({
    blob: new Blob(["%PDF-1.7"], { type: "application/pdf" }),
    mediaType: "application/pdf",
  });
  mocks.createObjectUrl.mockReturnValue("blob:cortex-document-1");
  vi.stubGlobal("URL", {
    createObjectURL: mocks.createObjectUrl,
    revokeObjectURL: mocks.revokeObjectUrl,
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

describe("FinanceInvoicesPanel document access", () => {
  it("uses an authenticated Blob URL for PDF preview and download, never a protected API navigation", async () => {
    const user = userEvent.setup();
    const view = renderPanel();

    await user.click(screen.getByRole("button", { name: "Documentos" }));

    const preview = await screen.findByTitle("Pré-visualização de nota.pdf");
    expect(mocks.buscarConteudoDocumento).toHaveBeenCalledWith(
      "invoice-1",
      "document-1",
      "obra-1",
    );
    expect(preview.tagName).toBe("IFRAME");
    expect(preview).toHaveAttribute("src", "blob:cortex-document-1");
    expect(preview).toHaveAttribute("sandbox", "");
    expect(view.container.querySelector("object")).toBeNull();

    const download = screen.getByRole("link", { name: "Baixar documento" });
    expect(download).toHaveAttribute("href", "blob:cortex-document-1");
    expect(download).toHaveAttribute("download", "nota.pdf");
    expect(download.getAttribute("href")).not.toContain("/api/");

    view.unmount();
    await waitFor(() => {
      expect(mocks.revokeObjectUrl).toHaveBeenCalledWith(
        "blob:cortex-document-1",
      );
    });
  });

  it("never embeds unsafe document media types", async () => {
    const user = userEvent.setup();
    mocks.buscarConteudoDocumento.mockResolvedValue({
      blob: new Blob(["<svg/>"] , { type: "image/svg+xml" }),
      mediaType: "image/svg+xml",
    });
    const view = renderPanel();

    await user.click(screen.getByRole("button", { name: "Documentos" }));

    expect(await screen.findByText(
      /Pré-visualização indisponível para este tipo de arquivo/i,
    )).toBeVisible();
    expect(view.container.querySelector("iframe")).toBeNull();
    expect(view.container.querySelector("img.finance-document-preview-image"))
      .toBeNull();
    expect(screen.getByRole("link", { name: "Baixar documento" }))
      .toHaveAttribute("href", "blob:cortex-document-1");
  });
});
