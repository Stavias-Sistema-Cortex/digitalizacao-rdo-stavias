import { readFile } from "node:fs/promises";

import * as XLSX from "@e965/xlsx";
import { beforeEach, describe, expect, it, vi } from "vitest";

const pdfRuntime = vi.hoisted(() => ({
  getDocument: vi.fn(),
}));

vi.mock("pdfjs-dist/legacy/build/pdf.mjs", () => ({
  GlobalWorkerOptions: {
    workerSrc: "",
  },
  getDocument: pdfRuntime.getDocument,
}));

vi.mock("pdfjs-dist/legacy/build/pdf.worker.mjs?url", () => ({
  default: "pdf.worker.mjs",
}));

import { importarRdoArquivo } from "./importRdoExcel";
import { readValidatedRdoImport } from "./rdoImportPolicy";
import {
  RDO_IMPORT_LIMITS,
} from "../../lib/files/rdoImportResourcePolicy";

const PDF_MIME = "application/pdf";
const XLSX_MIME =
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
const MAX_FILE_BYTES = RDO_IMPORT_LIMITS.fileBytes;
const MAX_PDF_PAGES = RDO_IMPORT_LIMITS.pdfPages;
const MAX_PDF_TEXT_ITEMS = RDO_IMPORT_LIMITS.pdfTextItems;
const MAX_PDF_TEXT_CHARACTERS = RDO_IMPORT_LIMITS.pdfTextChars;
const MAX_WORKBOOK_SHEETS = RDO_IMPORT_LIMITS.workbookSheets;
const MAX_WORKBOOK_ROWS = RDO_IMPORT_LIMITS.workbookRowsPerSheet;
const MAX_WORKBOOK_COLUMNS =
  RDO_IMPORT_LIMITS.workbookColumnsPerSheet;
const MAX_WORKBOOK_CELLS = RDO_IMPORT_LIMITS.workbookCells;
const TEMPLATE = new URL("./export/RDO-v1.xlsx", import.meta.url);

interface PdfTextItem {
  str: string;
  transform: number[];
}

function pdfFile(
  name = "RDO-0042.pdf",
  type = PDF_MIME,
): File {
  return new File(
    [new TextEncoder().encode("%PDF-1.7\n% fixture")],
    name,
    { type },
  );
}

function spreadsheetFile(
  rowsBySheet: ReadonlyArray<{
    name: string;
    rows: unknown[][];
  }>,
): File {
  const workbook = XLSX.utils.book_new();
  for (const entry of rowsBySheet) {
    XLSX.utils.book_append_sheet(
      workbook,
      XLSX.utils.aoa_to_sheet(entry.rows),
      entry.name,
    );
  }
  const content = XLSX.write(workbook, {
    bookType: "xlsx",
    type: "array",
  });
  return new File([content], "RDO-limites.xlsx", {
    type: XLSX_MIME,
  });
}

function installPdfDocument(
  options: {
    pages?: number;
    items?: PdfTextItem[];
  } = {},
) {
  const items =
    options.items ??
    [
      {
        str: "Nº RDO: RDO-0042",
        transform: [1, 0, 0, 1, 10, 100],
      },
      {
        str: "Data: 27/07/2026",
        transform: [1, 0, 0, 1, 10, 90],
      },
    ];
  let emitted = false;
  const reader = {
    read: vi.fn(async () => {
      if (emitted) {
        return { done: true };
      }
      emitted = true;
      return {
        done: false,
        value: { items },
      };
    }),
    cancel: vi.fn(async () => undefined),
    releaseLock: vi.fn(),
  };
  const getPage = vi.fn(async () => ({
    streamTextContent: vi.fn(() => ({
      getReader: () => reader,
    })),
  }));
  const destroy = vi.fn(async () => undefined);

  pdfRuntime.getDocument.mockReturnValue({
    promise: Promise.resolve({
      numPages: options.pages ?? 1,
      getPage,
      destroy,
    }),
    destroy,
  });

  return {
    destroy,
    getPage,
    reader,
  };
}

describe("importação local de RDO: limites de recursos", () => {
  beforeEach(() => {
    pdfRuntime.getDocument.mockReset();
  });

  it("rejeita arquivo acima do limite antes de ler seus bytes", async () => {
    installPdfDocument();
    const file = pdfFile();
    Object.defineProperty(file, "size", {
      configurable: true,
      value: MAX_FILE_BYTES + 1,
    });
    const arrayBuffer = vi.spyOn(file, "arrayBuffer");

    await expect(importarRdoArquivo(file, "Ana")).rejects.toThrow(
      /10 MiB/,
    );
    expect(arrayBuffer).not.toHaveBeenCalled();
  });

  it("rejeita extensão e tipo declarado incompatíveis", async () => {
    const file = pdfFile("RDO-0042.xlsx", PDF_MIME);

    await expect(importarRdoArquivo(file, "Ana")).rejects.toThrow(
      /extensão.*tipo|tipo.*extensão/i,
    );
  });

  it("não confia no MIME quando o conteúdo diverge da extensão", async () => {
    const file = pdfFile("RDO-0042.xlsx", XLSX_MIME);

    await expect(importarRdoArquivo(file, "Ana")).rejects.toThrow(
      /assinatura.*extensão/i,
    );
  });

  it("rejeita PDF acima do limite de páginas antes de extrair uma página", async () => {
    const document = installPdfDocument({
      pages: MAX_PDF_PAGES + 1,
    });

    await expect(
      importarRdoArquivo(pdfFile(), "Ana"),
    ).rejects.toThrow(new RegExp(`${MAX_PDF_PAGES} páginas`));
    expect(document.getPage).not.toHaveBeenCalled();
  });

  it("rejeita página PDF com trabalho de texto acima do limite", async () => {
    installPdfDocument({
      items: Array.from(
        { length: MAX_PDF_TEXT_ITEMS + 1 },
        (_, index) => ({
          str: `item-${index}`,
          transform: [1, 0, 0, 1, index, 100],
        }),
      ),
    });

    await expect(
      importarRdoArquivo(pdfFile(), "Ana"),
    ).rejects.toThrow(/itens de texto/i);
  });

  it("rejeita item PDF cujo texto isolado excede o limite", async () => {
    installPdfDocument({
      items: [
        {
          str: "x".repeat(MAX_PDF_TEXT_CHARACTERS + 1),
          transform: [1, 0, 0, 1, 10, 100],
        },
      ],
    });

    await expect(
      importarRdoArquivo(pdfFile(), "Ana"),
    ).rejects.toThrow(
      new RegExp(`${MAX_PDF_TEXT_CHARACTERS} caracteres de texto`),
    );
  });

  it("traduz PDF malformado em erro seguro e acionável", async () => {
    pdfRuntime.getDocument.mockReturnValue({
      promise: Promise.reject(new Error("xref internals must not leak")),
      destroy: vi.fn(async () => undefined),
    });

    await expect(
      importarRdoArquivo(pdfFile(), "Ana"),
    ).rejects.toThrow(/PDF.*corrompido|corrompido.*PDF/i);
    await expect(
      importarRdoArquivo(pdfFile(), "Ana"),
    ).rejects.not.toThrow(/xref internals/i);
  });

  it("rejeita planilha acima do limite de abas", async () => {
    const file = spreadsheetFile(
      Array.from(
        { length: MAX_WORKBOOK_SHEETS + 1 },
        (_, index) => ({
          name: index === 0 ? "frente" : `aba-${index + 1}`,
          rows: [
            [
              index === 0
                ? "RELATÓRIO DIÁRIO DE OBRA"
                : `conteúdo-${index}`,
            ],
          ],
        }),
      ),
    );

    await expect(importarRdoArquivo(file, "Ana")).rejects.toThrow(
      new RegExp(`${MAX_WORKBOOK_SHEETS} abas`),
    );
  });

  it("rejeita planilha acima do limite de linhas em uma aba", async () => {
    const rows = Array.from(
      { length: MAX_WORKBOOK_ROWS + 1 },
      (_, index) => [
        index === 0
          ? "RELATÓRIO DIÁRIO DE OBRA"
          : `linha-${index + 1}`,
      ],
    );

    await expect(
      importarRdoArquivo(
        spreadsheetFile([{ name: "frente", rows }]),
        "Ana",
      ),
    ).rejects.toThrow(
      new RegExp(`${MAX_WORKBOOK_ROWS} linhas`),
    );
  });

  it("rejeita planilha acima do limite de colunas em uma aba", async () => {
    const row = Array.from(
      { length: MAX_WORKBOOK_COLUMNS + 1 },
      (_, index) =>
        index === 0
          ? "RELATÓRIO DIÁRIO DE OBRA"
          : `coluna-${index + 1}`,
    );

    await expect(
      importarRdoArquivo(
        spreadsheetFile([{ name: "frente", rows: [row] }]),
        "Ana",
      ),
    ).rejects.toThrow(
      new RegExp(`${MAX_WORKBOOK_COLUMNS} colunas`),
    );
  });

  it("rejeita planilha acima do limite de células preenchidas", async () => {
    const rows = Array.from(
      { length: 800 },
      (_, rowIndex) =>
        Array.from(
          { length: 126 },
          (_, columnIndex) =>
            rowIndex === 0 && columnIndex === 0
              ? "RELATÓRIO DIÁRIO DE OBRA"
              : `c-${rowIndex}-${columnIndex}`,
        ),
    );
    expect(rows.length * rows[0].length).toBeGreaterThan(
      MAX_WORKBOOK_CELLS,
    );

    await expect(
      importarRdoArquivo(
        spreadsheetFile([{ name: "frente", rows }]),
        "Ana",
      ),
    ).rejects.toThrow(
      new RegExp(`${MAX_WORKBOOK_CELLS} células`),
    );
  });

  it("traduz planilha malformada em erro seguro e acionável", async () => {
    const file = new File(
      [new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0, 0])],
      "RDO-corrompido.xlsx",
      { type: XLSX_MIME },
    );

    await expect(importarRdoArquivo(file, "Ana")).rejects.toThrow(
      /planilha.*corrompido|corrompido.*planilha/i,
    );
  });

  it("rejeita planilha compactada que declara expansão excessiva", async () => {
    const original = spreadsheetFile([
      {
        name: "frente",
        rows: [["RELATÓRIO DIÁRIO DE OBRA"]],
      },
    ]);
    const data = new Uint8Array(await original.arrayBuffer());
    let centralDirectory = -1;

    for (let index = 0; index <= data.length - 4; index += 1) {
      if (
        data[index] === 0x50 &&
        data[index + 1] === 0x4b &&
        data[index + 2] === 0x01 &&
        data[index + 3] === 0x02
      ) {
        centralDirectory = index;
        break;
      }
    }

    if (centralDirectory < 0) {
      throw new Error("Fixture ZIP sem diretório central.");
    }

    new DataView(
      data.buffer,
      data.byteOffset,
      data.byteLength,
    ).setUint32(
      centralDirectory + 24,
      33 * 1024 * 1024,
      true,
    );
    const file = new File(
      [data],
      "RDO-expansao.xlsx",
      { type: XLSX_MIME },
    );

    await expect(readValidatedRdoImport(file)).rejects.toThrow(
      /conteúdo interno grande demais/i,
    );
  });

  it("aceita o template XLSX válido do RDO", async () => {
    const content = await readFile(TEMPLATE);
    const file = new File([content], "RDO-v1.xlsx", {
      type: XLSX_MIME,
    });

    const imported = await importarRdoArquivo(file, "Ana");

    expect(imported.summary).toContain("Importado de RDO-v1.xlsx");
    expect(imported.draft.numeroRdo).toBe("RDO-v1");
  });

  it("mantém a importação de PDF com texto selecionável", async () => {
    const document = installPdfDocument();

    const imported = await importarRdoArquivo(pdfFile(), "Ana");

    expect(imported.draft.numeroRdo).toBe("RDO-0042");
    expect(imported.draft.dataRdo).toBe("2026-07-27");
    expect(imported.summary).toContain("texto do PDF identificado");
    expect(document.getPage).toHaveBeenCalledOnce();
  });
}, 15_000);
