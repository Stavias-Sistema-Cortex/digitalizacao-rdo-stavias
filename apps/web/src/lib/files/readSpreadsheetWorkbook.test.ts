// @vitest-environment jsdom
import { describe, expect, it } from "vitest";

import {
  readSpreadsheetWorkbook,
} from "./readSpreadsheetWorkbook";

async function workbookFile(
  sheets: Array<{
    name: string;
    data: unknown[][];
    formula?: string;
  }>,
): Promise<File> {
  const XLSX = await import("@e965/xlsx");
  const workbook = XLSX.utils.book_new();

  for (const definition of sheets) {
    const sheet = XLSX.utils.aoa_to_sheet(definition.data);
    if (definition.formula) {
      sheet.A1 = {
        t: "n",
        f: definition.formula,
        v: 2,
      };
    }
    XLSX.utils.book_append_sheet(
      workbook,
      sheet,
      definition.name,
    );
  }

  const bytes = XLSX.write(workbook, {
    bookType: "xlsx",
    type: "array",
  });
  return new File([bytes], "RDO-v1.xlsx", {
    type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  });
}

describe("bounded spreadsheet reader", () => {
  it("preserves a normal two-sheet RDO workbook and Brazilian text", async () => {
    const file = await workbookFile([
      {
        name: "v.1 RDO frente",
        data: [["RELATÓRIO DIÁRIO DE OBRA"], ["Mão de obra"]],
      },
      {
        name: "v.1 RDO verso",
        data: [["CONTROLE GEOMÉTRICO"], ["Observações gerais"]],
      },
    ]);

    await expect(readSpreadsheetWorkbook(file)).resolves.toEqual([
      {
        sheet: "v.1 RDO frente",
        data: [["RELATÓRIO DIÁRIO DE OBRA"], ["Mão de obra"]],
      },
      {
        sheet: "v.1 RDO verso",
        data: [["CONTROLE GEOMÉTRICO"], ["Observações gerais"]],
      },
    ]);
  });

  it("rejects formulas instead of importing cached or executable workbook logic", async () => {
    const file = await workbookFile([
      {
        name: "frente",
        data: [[2]],
        formula: "1+1",
      },
    ]);

    await expect(readSpreadsheetWorkbook(file)).rejects.toThrow(
      "fórmulas",
    );
  });

  it("rejects a sheet dimension over the configured row limit before JSON materialization", async () => {
    const file = await workbookFile([
      {
        name: "frente",
        data: [["linha 1"], ["linha 2"]],
      },
    ]);

    await expect(
      readSpreadsheetWorkbook(file, {
        limits: {
          rowsPerSheet: 1,
        },
      }),
    ).rejects.toThrow("mais de 1 linhas");
  });

  it("enforces sheet, column, and cell limits before row materialization", async () => {
    const twoSheets = await workbookFile([
      { name: "frente", data: [["a"]] },
      { name: "verso", data: [["b"]] },
    ]);
    await expect(
      readSpreadsheetWorkbook(twoSheets, {
        limits: { sheets: 1 },
      }),
    ).rejects.toThrow("mais de 1 abas");

    const twoColumns = await workbookFile([
      { name: "frente", data: [["a", "b"]] },
    ]);
    await expect(
      readSpreadsheetWorkbook(twoColumns, {
        limits: { columnsPerSheet: 1 },
      }),
    ).rejects.toThrow("mais de 1 colunas");
    await expect(
      readSpreadsheetWorkbook(twoColumns, {
        limits: { cells: 1 },
      }),
    ).rejects.toThrow("limite de 1 células");
  });

  it("returns a safe Portuguese error for a malformed ZIP workbook", async () => {
    const file = new File(
      [new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0x00])],
      "corrompido.xlsx",
      {
        type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      },
    );

    await expect(readSpreadsheetWorkbook(file)).rejects.toThrow(
      "Não foi possível ler a planilha de RDO",
    );
  });
});
