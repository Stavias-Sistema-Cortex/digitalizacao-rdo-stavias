// @vitest-environment jsdom
import { jsPDF } from "jspdf";
import { describe, expect, it, vi } from "vitest";

import { importarRdoArquivo } from "./importRdoExcel";

// Vitest resolves Vite's ?url module to an HTTP-style /node_modules path,
// while pdf.js's Node fake worker needs a file URL. The parser itself stays
// real; only the test-runtime worker URL is adapted.
vi.mock("pdfjs-dist/legacy/build/pdf.worker.mjs?url", () => ({
  default: `file://${process.cwd()}/node_modules/pdfjs-dist/legacy/build/pdf.worker.mjs`,
}));

function selectablePdfFile(lines: string[], name = "rdo-importado.pdf"): File {
  const document = new jsPDF({ unit: "pt", format: "a4" });
  lines.forEach((line, index) => {
    document.text(line, 40, 44 + (index * 18));
  });

  return new File([document.output("arraybuffer")], name, {
    type: "application/pdf",
  });
}

describe("RDO PDF import", () => {
  it("extracts a selectable RDO PDF into an editable draft without a worksheet fallback", async () => {
    const imported = await importarRdoArquivo(
      selectablePdfFile([
        "RELATÓRIO DIÁRIO DE OBRA",
        "Nº RDO: RDO-0042",
        "Cliente: Cliente Norte",
        "Nº DA OBRA: CTR-9",
        "Rodovia: BR-101",
        "15/07/2026",
        "Apontador RDO: Ana Apontadora",
        "Encarregado da Obra: Enzo Encarregado",
        "Fiscalização de Campo: Flávia Fiscal",
        "Turno: NOTURNO",
        "Hora Início: 07:30",
        "Hora Fim: 17:15",
      ]),
      "Sessão atual",
    );

    expect(imported.draft).toMatchObject({
      numeroRdo: "RDO-0042",
      dataRdo: "2026-07-15",
      cliente: "Cliente Norte",
      contrato: "CTR-9",
      rodovia: "BR-101",
      turno: "NOTURNO",
      horaInicio: "07:30",
      horaFim: "17:15",
      preenchidoPor: "Ana Apontadora",
      apontadorRdo: "Ana Apontadora",
      encarregadoObra: "Enzo Encarregado",
      fiscalizacaoCampo: "Flávia Fiscal",
    });
    expect(imported.summary).toContain("texto do PDF identificado");
    expect(imported.warnings).toEqual(expect.arrayContaining([
      expect.stringContaining("texto selecionável"),
      expect.stringContaining("CTR-9"),
    ]));
    expect(imported.draft.observacoes).toContain(
      "Texto extraído do PDF importado:",
    );
  });

  it("rejects a PDF without selectable text instead of inventing an RDO", async () => {
    await expect(importarRdoArquivo(
      selectablePdfFile([], "rdo-escaneado.pdf"),
      "Sessão atual",
    )).rejects.toThrow(
      "Não encontrei texto selecionável neste PDF. Para PDF escaneado, será necessário OCR antes da importação.",
    );
  });

  it("returns a safe Portuguese error for a malformed PDF", async () => {
    const malformed = new File(
      ["%PDF-arquivo-corrompido"],
      "rdo-corrompido.pdf",
      {
        type: "application/pdf",
      },
    );

    await expect(
      importarRdoArquivo(malformed, "Sessão atual"),
    ).rejects.toThrow(
      "Não foi possível ler o PDF de RDO",
    );
  });
});
