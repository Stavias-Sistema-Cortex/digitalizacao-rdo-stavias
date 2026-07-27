import { jsPDF } from "jspdf";
import { describe, expect, it, vi } from "vitest";

vi.mock("pdfjs-dist/legacy/build/pdf.worker.mjs?url", () => ({
  default: new URL(
    "../../../node_modules/pdfjs-dist/legacy/build/pdf.worker.mjs",
    import.meta.url,
  ).href,
}));

import { importarRdoArquivo } from "./importRdoExcel";

describe("importação local de RDO: PDF real", () => {
  it("aceita PDF válido com texto selecionável sem OCR", async () => {
    const document = new jsPDF();
    document.text("Nº RDO: RDO-0099", 20, 20);
    document.text("Data: 27/07/2026", 20, 30);
    document.text("Cliente: Município de Exemplo", 20, 40);
    const content = document.output("arraybuffer");
    const file = new File(
      [content],
      "RDO-0099.pdf",
      { type: "application/pdf" },
    );

    const imported = await importarRdoArquivo(file, "Ana");

    expect(imported.draft.numeroRdo).toBe("RDO-0099");
    expect(imported.draft.dataRdo).toBe("2026-07-27");
    expect(imported.draft.cliente).toBe("Município de Exemplo");
    expect(imported.draft.observacoes).toContain(
      "Texto extraído do PDF importado",
    );
  });
});
