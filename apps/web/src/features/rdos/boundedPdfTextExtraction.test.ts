import { describe, expect, it, vi } from "vitest";

import {
  extractBoundedPdfLines,
  type PdfDocumentForTextExtraction,
} from "./boundedPdfTextExtraction";

function pdfDocument(
  pages: unknown[][],
): PdfDocumentForTextExtraction {
  return {
    numPages: pages.length,
    getPage: vi.fn(async (pageNumber: number) => ({
      getTextContent: async () => ({
        items: pages[pageNumber - 1],
      }),
    })),
  };
}

describe("bounded PDF text extraction", () => {
  it("rejects the page count before iterating document pages", async () => {
    const document = pdfDocument([[], []]);

    await expect(
      extractBoundedPdfLines(document, { pages: 1 }),
    ).rejects.toThrow("mais de 1 páginas");
    expect(document.getPage).not.toHaveBeenCalled();
  });

  it("rejects total text items over the configured limit", async () => {
    const item = { str: "RDO", transform: [1, 0, 0, 1, 10, 10] };
    const document = pdfDocument([[item, item, item]]);

    await expect(
      extractBoundedPdfLines(document, { textItems: 2 }),
    ).rejects.toThrow("mais de 2 itens de texto");
  });

  it("rejects extracted characters over the configured limit", async () => {
    const document = pdfDocument([[
      { str: "abc", transform: [1, 0, 0, 1, 10, 10] },
      { str: "def", transform: [1, 0, 0, 1, 20, 10] },
    ]]);

    await expect(
      extractBoundedPdfLines(document, { textChars: 5 }),
    ).rejects.toThrow("mais de 5 caracteres");
  });

  it("counts text characters even when an item's positioning metadata is malformed", async () => {
    const document = pdfDocument([[
      { str: "abcdef" },
    ]]);

    await expect(
      extractBoundedPdfLines(document, { textChars: 5 }),
    ).rejects.toThrow("mais de 5 caracteres");
  });

  it("groups selectable Brazilian text in visual order", async () => {
    const document = pdfDocument([[
      {
        str: "Cliente: Córtex",
        transform: [1, 0, 0, 1, 40, 700],
      },
      {
        str: "RDO-0042",
        transform: [1, 0, 0, 1, 150, 720],
      },
      {
        str: "Nº RDO:",
        transform: [1, 0, 0, 1, 40, 720],
      },
    ]]);

    await expect(extractBoundedPdfLines(document)).resolves.toEqual([
      "Nº RDO: RDO-0042",
      "Cliente: Córtex",
    ]);
  });
});
